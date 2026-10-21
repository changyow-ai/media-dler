#!/usr/bin/env python3
"""Developmental weighted shadow scorer over LOCAL raw feature reports.

No media decoding, OCR, network, source edits, automatic skipping, or calibrated
probability claim. Labels, IDs, paths, exact matches and v1 evidence flags do not
enter scoring. Missing families score zero without renormalizing other families.
The positive sum is S + A + capped content C; P remains a diagnostic within C.
"""
import argparse
import csv
import hashlib
import json
import math
import re
import unicodedata
from pathlib import Path

POLICY = "weighted-v3-layout-development"
SETTINGS = {
    "version": POLICY, "weights": {"S": 40, "A": 25, "P": 25}, "threshold": 55,
    "scene_return_max": 20, "scene_cut_max": 5, "scene_temporal_max": 15,
    "scene_seam_floor": 1 / 255, "scene_cut_scale": 4 / 255,
    "scene_contrast_quality_policy": "clamp_min_original_seam_over_cut_scale_multiplies_return_and_temporal",
    "scene_info_scale": 1 / 255, "scene_native_multiplier": 4, "scene_join_floor": 2 / 255,
    "audio_gain_full_db": 12, "audio_peak_floor": 1,
    "ocr_expected_frames": 3, "ocr_confidence_power": .5,
    "domain_share": .65, "semantic_share": .35, "outside_domain_novelty_factor": .5,
    "fuzzy_similarity_deadzone": .5, "programme_negative_max": 40,
    "layout_height_scale": .12, "layout_central_rect": [.2, .25, .8, .75],
    "layout_max": 50, "content_cap": 60, "positive_raw_cap": 125, "overlay_negative_max": 20,
    "overlay_edge_height": .2, "commercial_semantic_power": 2,
    "geometry_policy": "finite_nonbool_positive_size_clip_to_unit_image_then_measure",
    "commercial_union_policy": "integral_of_max_observation_weight_over_rectangle_union",
    "content_formula": "C=min(60,P+50*sum_distinct_frame_Lt/3)",
    "overlay_formula": "O=20*sum_distinct_frame(pt*et*(1-Lt))/3",
    "final_formula": "clamp_0_100(S+A+C-O-programme_negative)",
    "max_text_characters": 512, "max_latin_words": 64,
    "max_word_characters": 40, "max_chinese_windows_per_term": 128,
    "max_observations_per_frame": 64,
    "missing_cue_policy": "zero_no_renormalization_no_evidence_veto",
    "score_interpretation": "uncalibrated_developmental_evidence_score_not_probability",
}
LEXICON = {
    "promotion_latin": ["register", "registration", "deposit", "bonus", "casino", "slot", "slots",
                        "gambling", "game", "games", "betting", "bet", "rebate", "signup"],
    "promotion_chinese": ["注册", "註冊", "充值", "投注", "博彩", "棋牌", "奖金", "獎金", "中奖",
                          "中獎", "大奖", "大獎", "娱乐城", "遊戲", "游戏"],
    "programme_latin": ["cast", "elenco", "traducao", "dublagem", "starring", "screenplay"],
    "programme_chinese": ["配音", "演員", "演员", "字幕製作", "字幕制作"],
}
# ASCII boundaries deliberately permit domains immediately adjacent to Chinese.
DOMAIN = re.compile(r"(?<![a-z0-9.-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}(?![a-z0-9.-])")
LATIN = re.compile(r"[a-z]+")
CHINESE = re.compile(r"[\u3400-\u9fff]+")


def numeric(value):
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return None
    try:
        value = float(value)
    except OverflowError:
        return None
    return value if math.isfinite(value) else None


def clamp(value):
    return max(0.0, min(1.0, value))


def normalize(text):
    text = unicodedata.normalize("NFKC", text).casefold()
    text = "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.combining(c))
    return re.sub(r"\s*\.\s*", ".", text)


def similarity(left, right):
    """Normalized Levenshtein similarity; bounded tokens/windows, not substrings."""
    if left == right:
        return 1.0
    previous = list(range(len(right) + 1))
    for i, a in enumerate(left, 1):
        row = [i]
        for j, b in enumerate(right, 1):
            row.append(min(row[-1] + 1, previous[j] + 1, previous[j - 1] + (a != b)))
        previous = row
    return 1 - previous[-1] / max(len(left), len(right), 1)


def fuzzy_quality(text, family):
    words = [w for w in LATIN.findall(text) if len(w) <= SETTINGS["max_word_characters"]][:SETTINGS["max_latin_words"]]
    best = {"quality": 0.0, "similarity": 0.0, "lexicon_term": None, "observed": None}
    for term in LEXICON[family + "_latin"] + LEXICON[family + "_chinese"]:
        if CHINESE.search(term):
            windows = []
            for run in CHINESE.findall(text):
                for length in range(max(1, len(term) - 1), len(term) + 2):
                    windows.extend(run[i:i + length] for i in range(max(0, len(run) - length + 1)))
                if len(windows) >= SETTINGS["max_chinese_windows_per_term"]:
                    break
            candidates = windows[:SETTINGS["max_chinese_windows_per_term"]]
        else:
            candidates = words
        for candidate in candidates:
            score = similarity(candidate, term)
            deadzone = SETTINGS["fuzzy_similarity_deadzone"]
            quality = clamp((score - deadzone) / (1 - deadzone))
            if quality > best["quality"]:
                best = {"quality": quality, "similarity": score, "lexicon_term": term, "observed": candidate}
    return best


def context(case, role):
    value = case.get("inputs", {}).get(role, {})
    return value if isinstance(value, dict) else {}


def scene_score(case):
    bridge = case.get("bridge_only_S", {})
    keys = ("joined_edge_mae", "start_seam_mae", "end_seam_mae")
    values = [numeric(bridge.get(k)) for k in keys]
    missing = [k for k, v in zip(keys, values) if v is None or not 0 <= v <= 1]
    missing += [r + "_context" for r in ("before", "after") if context(case, r).get("status") != "ok"]
    if missing:
        return {"points": 0.0, "parts": {}, "coverage": {"seams": 3 - sum(v is None for v in values), "expected": 3},
                "missing_reasons": missing}
    joined, first, last = values
    original = min(first, last)
    strength = clamp(1 - joined / max(original, SETTINGS["scene_seam_floor"]))
    contrast = clamp(original / SETTINGS["scene_cut_scale"])
    native = [numeric(context(case, r).get("visual", {}).get("native_adjacent_mae_p90")) for r in ("before", "after")]
    # The bridge stores the same raw measurements; accept either raw location.
    stored = bridge.get("side_native_adjacent_mae_p90", [])
    native = [v if v is not None else numeric(stored[i]) if i < len(stored) else None for i, v in enumerate(native)]
    valid = [v for v in native if v is not None and 0 <= v <= 1]
    baseline = max(valid) if valid else 0.0
    info = clamp(baseline / SETTINGS["scene_info_scale"])
    compatibility = 1 / (1 + joined / max(SETTINGS["scene_native_multiplier"] * baseline, SETTINGS["scene_join_floor"]))
    parts = {"return": SETTINGS["scene_return_max"] * strength * contrast,
             "cut_contrast": SETTINGS["scene_cut_max"] * contrast,
             "temporal": SETTINGS["scene_temporal_max"] * info * compatibility * contrast}
    return {"points": sum(parts.values()), "parts": parts,
            "measurements": {"joined_mae": joined, "minimum_original_seam_mae": original,
                             "native_p90": native, "return_strength": strength, "contrast_quality": contrast, "information": info, "compatibility": compatibility},
            "coverage": {"seams": 3, "expected": 3, "native_baselines": len(valid), "native_expected": 2},
            "missing_reasons": [] if len(valid) == 2 else ["missing_native_baseline"]}


def audio_values(case, role):
    audio = context(case, role).get("audio", {})
    rms, peak = numeric(audio.get("rms_pcm16")), numeric(audio.get("peak_pcm16"))
    if rms is None or peak is None or rms <= 0 or peak <= SETTINGS["audio_peak_floor"]:
        return None
    return rms, peak


def audio_score(case):
    candidate = audio_values(case, "candidate")
    sides = {}
    for role in ("before", "after"):
        outside = audio_values(case, role)
        if candidate is None or outside is None or context(case, role).get("status") != "ok":
            sides[role] = {"quality": 0.0, "available": False, "reason": "missing_audio_context_or_numerical_floor"}
        else:
            gain = 20 * math.log10(candidate[0] / outside[0])
            sides[role] = {"quality": clamp(gain / SETTINGS["audio_gain_full_db"]), "available": True, "rms_gain_db": gain}
    valid = [s["quality"] for s in sides.values() if s["available"]]
    return {"points": SETTINGS["weights"]["A"] * sum(valid) / len(valid) if valid else 0.0,
            "sides": sides, "coverage": {"available": len(valid), "expected": 2},
            "missing_reasons": [r + ":" + s["reason"] for r, s in sides.items() if not s["available"]]}


def frame_groups(raw):
    groups, missing = {}, []
    for sample in raw.get("samples", []):
        time = numeric(sample.get("time_seconds"))
        observations = sample.get("observations")
        if time is None or not isinstance(observations, list):
            missing.append("missing_sample_time_or_raw_observations")
            continue
        groups.setdefault(time, []).extend(observations)
        if sample.get("reason"):
            missing.append(str(sample["reason"]))
    return groups, missing


def observations(items):
    for index, item in enumerate(items[:SETTINGS["max_observations_per_frame"]]):
        if not isinstance(item, dict) or not isinstance(item.get("text"), str):
            continue
        confidence = numeric(item.get("confidence"))
        if confidence is None or not 0 <= confidence <= 1:
            continue
        text = normalize(item["text"][:SETTINGS["max_text_characters"]])
        if not text.strip():
            continue
        yield text, confidence ** SETTINGS["ocr_confidence_power"], item.get("box"), index


def intersection(left, right):
    result = (max(left[0], right[0]), max(left[1], right[1]), min(left[2], right[2]), min(left[3], right[3]))
    return result if result[2] > result[0] and result[3] > result[1] else None


def area(rect):
    return (rect[2] - rect[0]) * (rect[3] - rect[1]) if rect else 0.0


def image_box(box):
    if not isinstance(box, (list, tuple)) or len(box) != 4:
        return None
    values = [numeric(v) for v in box]
    if any(v is None for v in values):
        return None
    x, y, width, height = values
    if width <= 0 or height <= 0 or not math.isfinite(x + width) or not math.isfinite(y + height):
        return None
    rect = intersection((x, y, x + width, y + height), (0, 0, 1, 1))
    return rect if area(rect) > 0 else None


def weighted_union(rectangles, region=(0, 0, 1, 1)):
    """Exact rectangle integral of max weight, so overlaps/duplicates never sum."""
    clipped = [(intersection(rect, region), weight) for rect, weight in rectangles if weight > 0]
    clipped = [(rect, weight) for rect, weight in clipped if rect]
    xs = sorted({x for rect, _ in clipped for x in (rect[0], rect[2])})
    total = 0.0
    for left, right in zip(xs, xs[1:]):
        active = [(rect, weight) for rect, weight in clipped if rect[0] <= left and rect[2] >= right]
        ys = sorted({y for rect, _ in active for y in (rect[1], rect[3])})
        for top, bottom in zip(ys, ys[1:]):
            weight = max((weight for rect, weight in active if rect[1] <= top and rect[3] >= bottom), default=0)
            total += (right - left) * (bottom - top) * weight
    return total


def frame_layout(observations):
    commercial, details, missing = [], [], []
    large = 0.0
    for text, quality, box, index, domains, promo in observations:
        rect = image_box(box)
        if rect is None:
            missing.append("observation_%s:missing_or_invalid_unit_image_geometry" % index)
            continue
        height_quality = clamp((rect[3] - rect[1]) / SETTINGS["layout_height_scale"])
        overlap = clamp(area(intersection(rect, SETTINGS["layout_central_rect"])) / area(rect))
        contribution = quality * height_quality * overlap
        large = max(large, contribution)
        weight = quality * max(bool(domains), promo["quality"] ** SETTINGS["commercial_semantic_power"])
        if weight > 0:
            commercial.append((rect, weight))
        details.append({"observation_index": index, "normalized_text": text, "effective_box_xyxy": rect,
                        "confidence_quality": quality, "height_quality": height_quality,
                        "central_overlap_fraction": overlap, "large_quality": contribution, "commercial_weight": weight})
    union = weighted_union(commercial)
    edge = SETTINGS["overlay_edge_height"]
    edge_union = weighted_union(commercial, (0, 0, 1, edge)) + weighted_union(commercial, (0, 1 - edge, 1, 1))
    return {"L_t": large, "commercial_weighted_union_area": union,
            "commercial_edge_weighted_union_area": edge_union, "e_t": clamp(edge_union / union) if union else 0.0,
            "observations": details, "geometry_available": len(details), "missing_reasons": missing}


def text_score(case):
    raw = case.get("ocr", {})
    outside, missing, outside_available = {}, [], 0
    for role in ("before", "after"):
        groups, reasons = frame_groups(raw.get(role, {}))
        outside_available += bool(groups)
        missing.extend(role + ":" + reason for reason in reasons)
        if not groups:
            missing.append("missing_" + role + "_ocr_context")
        for items in groups.values():
            for text, quality, _, _ in observations(items):
                for domain in DOMAIN.findall(text):
                    outside[domain] = max(outside.get(domain, 0), quality)
    groups, reasons = frame_groups(raw.get("candidate", {}))
    missing.extend(reasons)
    frames = []
    for time, items in sorted(groups.items()):
        domain_quality, semantic_quality, programme_quality = 0.0, 0.0, 0.0
        matches, geometry_observations = [], []
        for text, confidence, box, index in observations(items):
            domains = sorted(set(DOMAIN.findall(text)))
            novel_quality = max((confidence * (1 - (1 - SETTINGS["outside_domain_novelty_factor"]) * outside.get(d, 0))
                                 for d in domains), default=0.0)
            promo, programme = fuzzy_quality(text, "promotion"), fuzzy_quality(text, "programme")
            geometry_observations.append((text, confidence, box, index, domains, promo))
            domain_quality = max(domain_quality, novel_quality)
            semantic_quality = max(semantic_quality, confidence * promo["quality"])
            programme_quality = max(programme_quality, confidence * programme["quality"])
            if domains or promo["quality"] or programme["quality"]:
                matches.append({"normalized_text": text, "confidence_quality": confidence, "domains": domains,
                                "domain_quality": novel_quality, "semantic": promo, "programme": programme})
        layout = frame_layout(geometry_observations)
        if not geometry_observations:
            layout["missing_reasons"].append("no_valid_text_confidence_observations_for_geometry")
        p_t = SETTINGS["domain_share"] * domain_quality + SETTINGS["semantic_share"] * semantic_quality
        layout["p_t"] = p_t
        layout["O_t"] = SETTINGS["overlay_negative_max"] * p_t * layout["e_t"] * (1 - layout["L_t"])
        frames.append({"time_seconds": time, "domain_quality": domain_quality, "semantic_quality": semantic_quality,
                       "programme_quality": programme_quality, "matches": matches, "layout": layout})
    expected = SETTINGS["ocr_expected_frames"]
    means = {kind: clamp(sum(f[kind + "_quality"] for f in frames) / expected)
             for kind in ("domain", "semantic", "programme")}
    points = SETTINGS["weights"]["P"] * (SETTINGS["domain_share"] * means["domain"] + SETTINGS["semantic_share"] * means["semantic"])
    if len(frames) < expected:
        missing.append("missing_candidate_ocr_sample_times")
    return {"points": points, "frames": frames, "mean_qualities": means, "outside_domains": sorted(outside), "outside_domain_qualities": outside,
            "coverage": {"available": len(frames), "expected": expected,
                         "outside_available": outside_available, "outside_expected": 2},
            "missing_reasons": missing, "programme_negative": SETTINGS["programme_negative_max"] * means["programme"]}


def content_score(text):
    expected = SETTINGS["ocr_expected_frames"]
    large = sum(f["layout"]["L_t"] for f in text["frames"]) / expected
    uncapped = text["points"] + SETTINGS["layout_max"] * large
    overlay = sum(f["layout"]["O_t"] for f in text["frames"]) / expected
    frames = [{"time_seconds": f["time_seconds"], **{key: value for key, value in f["layout"].items() if key != "observations"}}
              for f in text["frames"]]
    missing = list(text["missing_reasons"])
    missing.extend("time_%s:%s" % (f["time_seconds"], reason) for f in frames for reason in f["missing_reasons"])
    return {"points": min(SETTINGS["content_cap"], uncapped), "P_points": text["points"], "L": large,
            "layout_points": SETTINGS["layout_max"] * large, "uncapped_points": uncapped,
            "cap": SETTINGS["content_cap"], "overlay_negative": overlay, "frames": frames,
            "coverage": {"geometry_frames_available": sum(f["geometry_available"] > 0 for f in frames), "expected": expected},
            "missing_reasons": missing}


def score_case(case):
    text = text_score(case)
    families = {"S": scene_score(case), "A": audio_score(case), "P": text, "C": content_score(text)}
    negative, overlay = text["programme_negative"], families["C"]["overlay_negative"]
    positive = sum(families[key]["points"] for key in ("S", "A", "C"))
    score = 100 * clamp((positive - overlay - negative) / 100)
    valid = context(case, "candidate").get("status") == "ok" and case.get("candidate_gate") == "valid"
    return {"id": case.get("id"), "annotations": case.get("annotations", {}), "families": families,
            "raw_positive_points": positive, "positive_families": ["S", "A", "C"],
            "soft_negative": {"programme": negative, "edge_overlay": overlay},
            "diagnostics": {"top_color_bin_median": numeric(context(case, "candidate").get("visual", {}).get("top_color_bin_median"))},
            "score": score, "candidate_source_gate": {"valid": valid, "reason": None if valid else "invalid_or_unverified_candidate_source"},
            "decision": "experimental_ad_candidate" if valid and score >= SETTINGS["threshold"] else "unknown",
            "action": "preserve"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    args = parser.parse_args(argv)
    raw = args.report.read_bytes()
    data = json.loads(raw)
    if not isinstance(data, dict) or not isinstance(data.get("cases"), list) or any(not isinstance(c, dict) for c in data["cases"]):
        parser.error("requires raw report object containing cases array")
    args.work_dir.mkdir(parents=True, exist_ok=False)
    report = {"schema": 1, "policy": POLICY, "settings": SETTINGS, "lexicon": LEXICON,
              "patterns": {"domain": DOMAIN.pattern, "latin": LATIN.pattern, "chinese": CHINESE.pattern},
              "source_input_sha256": hashlib.sha256(raw).hexdigest(), "source_policy": data.get("policy"),
              "action": "preserve", "cases": [score_case(c) for c in data["cases"]]}
    with (args.work_dir / "report.json").open("x") as output:
        json.dump(report, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write("\n")
    with (args.work_dir / "review.csv").open("x", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(["id", "S", "A", "P", "L", "C", "edge_overlay", "programme_negative", "raw_positive", "score", "decision", "action"])
        for case in report["cases"]:
            writer.writerow([case["id"], *[case["families"][f]["points"] for f in ("S", "A", "P")],
                             case["families"]["C"]["L"], case["families"]["C"]["points"], case["soft_negative"]["edge_overlay"],
                             case["soft_negative"]["programme"], case["raw_positive_points"], case["score"], case["decision"], case["action"]])
    print(str((args.work_dir / "report.json").resolve()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
