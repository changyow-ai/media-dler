#!/usr/bin/env python3
"""Developmental weighted shadow scorer over LOCAL raw feature reports.

No media decoding, OCR, network, source edits, automatic skipping, or calibrated
probability claim. Labels, IDs, paths, exact matches and v1 evidence flags do not
enter scoring. Missing families score zero without renormalizing other families.
"""
import argparse
import csv
import hashlib
import json
import math
import re
import unicodedata
from pathlib import Path

POLICY = "weighted-v2.1-development"
SETTINGS = {
    "version": POLICY, "weights": {"S": 40, "A": 25, "P": 25, "V": 10}, "threshold": 55,
    "scene_return_max": 20, "scene_cut_max": 5, "scene_temporal_max": 15,
    "scene_seam_floor": 1 / 255, "scene_cut_scale": 4 / 255,
    "scene_contrast_quality_policy": "clamp_min_original_seam_over_cut_scale_multiplies_return_and_temporal",
    "scene_info_scale": 1 / 255, "scene_native_multiplier": 4, "scene_join_floor": 2 / 255,
    "audio_gain_full_db": 12, "audio_peak_floor": 1,
    "ocr_expected_frames": 3, "ocr_confidence_power": .5,
    "domain_share": .65, "semantic_share": .35, "outside_domain_novelty_factor": .5,
    "fuzzy_similarity_deadzone": .5, "programme_negative_max": 40,
    "text_color_discount_max": 3, "max_text_characters": 512, "max_latin_words": 64,
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
    return (float(value) if isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) else None)


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
    for item in items[:SETTINGS["max_observations_per_frame"]]:
        if not isinstance(item, dict) or not isinstance(item.get("text"), str):
            continue
        confidence = numeric(item.get("confidence"))
        if confidence is None or not 0 <= confidence <= 1:
            continue
        yield normalize(item["text"][:SETTINGS["max_text_characters"]]), confidence ** SETTINGS["ocr_confidence_power"]


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
            for text, quality in observations(items):
                for domain in DOMAIN.findall(text):
                    outside[domain] = max(outside.get(domain, 0), quality)
    groups, reasons = frame_groups(raw.get("candidate", {}))
    missing.extend(reasons)
    frames = []
    for time, items in sorted(groups.items()):
        domain_quality, semantic_quality, programme_quality = 0.0, 0.0, 0.0
        matches = []
        for text, confidence in observations(items):
            domains = sorted(set(DOMAIN.findall(text)))
            novel_quality = max((confidence * (1 - (1 - SETTINGS["outside_domain_novelty_factor"]) * outside.get(d, 0))
                                 for d in domains), default=0.0)
            promo, programme = fuzzy_quality(text, "promotion"), fuzzy_quality(text, "programme")
            domain_quality = max(domain_quality, novel_quality)
            semantic_quality = max(semantic_quality, confidence * promo["quality"])
            programme_quality = max(programme_quality, confidence * programme["quality"])
            if domains or promo["quality"] or programme["quality"]:
                matches.append({"normalized_text": text, "confidence_quality": confidence, "domains": domains,
                                "domain_quality": novel_quality, "semantic": promo, "programme": programme})
        frames.append({"time_seconds": time, "domain_quality": domain_quality, "semantic_quality": semantic_quality,
                       "programme_quality": programme_quality, "matches": matches})
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


def visual_score(case):
    value = numeric(context(case, "candidate").get("visual", {}).get("top_color_bin_median"))
    return {"points": SETTINGS["weights"]["V"] * clamp(value) if value is not None else 0.0,
            "top_color_bin_median": value, "coverage": {"available": int(value is not None), "expected": 1},
            "missing_reasons": [] if value is not None else ["missing_flat_color_measurement"]}


def score_case(case):
    families = {"S": scene_score(case), "A": audio_score(case), "P": text_score(case), "V": visual_score(case)}
    negative = families["P"]["programme_negative"]
    discount = SETTINGS["text_color_discount_max"] * min(families["P"]["points"] / SETTINGS["weights"]["P"],
                                                        families["V"]["points"] / SETTINGS["weights"]["V"])
    score = 100 * clamp((sum(f["points"] for f in families.values()) - negative - discount) / 100)
    valid = context(case, "candidate").get("status") == "ok" and case.get("candidate_gate") == "valid"
    return {"id": case.get("id"), "annotations": case.get("annotations", {}), "families": families,
            "soft_negative": {"programme": negative}, "discount": {"text_color_correlation": discount},
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
        writer.writerow(["id", "S", "A", "P", "V", "programme_negative", "text_color_discount", "score", "decision", "action"])
        for case in report["cases"]:
            writer.writerow([case["id"], *[case["families"][f]["points"] for f in ("S", "A", "P", "V")],
                             case["soft_negative"]["programme"], case["discount"]["text_color_correlation"],
                             case["score"], case["decision"], case["action"]])
    print(str((args.work_dir / "report.json").resolve()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
