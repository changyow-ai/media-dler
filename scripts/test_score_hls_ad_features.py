#!/usr/bin/env python3
"""Synthetic feature tests only: no real media, labels, OCR or prior outcomes."""
import copy
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("score", Path(__file__).with_name("score-hls-ad-features.py"))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


def sample(text, time, confidence=1):
    return {"time_seconds": time, "observations": [{"text": text, "confidence": confidence, "box": [.2, .3, .6, .2]}]}


def strong():
    audio = {"rms_pcm16": 100, "peak_pcm16": 200}
    return {"id": "synthetic", "candidate_gate": "valid", "annotations": {},
            "inputs": {"candidate": {"status": "ok", "audio": {"rms_pcm16": 100 * 10 ** .6, "peak_pcm16": 1000},
                                     "visual": {"top_color_bin_median": 1}},
                       "before": {"status": "ok", "audio": dict(audio), "visual": {"native_adjacent_mae_p90": .01}},
                       "after": {"status": "ok", "audio": dict(audio), "visual": {"native_adjacent_mae_p90": .01}}},
            "bridge_only_S": {"joined_edge_mae": 0, "start_seam_mae": .2, "end_seam_mae": .2},
            "ocr": {"candidate": {"samples": [sample("123.com register", t) for t in (1, 2, 3)]}}}


def remove(case, family):
    if family == "S":
        case["bridge_only_S"] = {}
    elif family == "A":
        for value in case["inputs"].values():
            value.pop("audio", None)
    elif family == "P":
        case["ocr"] = {}
    elif family == "L":
        for frame in case["ocr"]["candidate"]["samples"]:
            for observation in frame["observations"]:
                observation.pop("box", None)


class WeightedTest(unittest.TestCase):
    def test_no_mandatory_family_and_content_cap_is_reachable_without_scene_audio(self):
        for absent in ("S", "A", "P", "L"):
            case = strong()
            remove(case, absent)
            result = p.score_case(case)
            with self.subTest(absent=absent):
                self.assertGreaterEqual(result["score"], 55)
                self.assertEqual(result["decision"], "experimental_ad_candidate")
                self.assertEqual(result["action"], "preserve")
        case = strong()
        remove(case, "S")
        remove(case, "A")
        result = p.score_case(case)
        self.assertEqual(result["families"]["C"]["uncapped_points"], 75)
        self.assertEqual(result["families"]["C"]["points"], 60)
        self.assertEqual(result["score"], 60)
        self.assertEqual(result["decision"], "experimental_ad_candidate")
        remove(case, "P")
        self.assertEqual(p.score_case(case)["score"], 0)

    def test_scene_audio_and_pure_color_do_not_get_renormalized(self):
        for keep in ("S", "A"):
            case = strong()
            remove(case, "P")
            remove(case, "A" if keep == "S" else "S")
            result = p.score_case(case)
            self.assertAlmostEqual(result["score"], p.SETTINGS["weights"][keep])
            self.assertEqual(result["decision"], "unknown")
        remove(case, keep)
        self.assertEqual(p.score_case(case)["score"], 0)
        self.assertEqual(p.score_case(case)["diagnostics"]["top_color_bin_median"], 1)

    def test_same_or_rotating_generic_domains_and_unicode_neighbors(self):
        same = strong()
        rotating = strong()
        rotating["ocr"]["candidate"]["samples"] = [sample(text, i) for i, text in enumerate(
            ("欢迎123.com注册", "歡迎888.vip註冊", "開啟777.cc充值"))]
        self.assertAlmostEqual(p.text_score(same)["points"], 25)
        self.assertAlmostEqual(p.text_score(rotating)["points"], 25)

    def test_low_confidence_and_one_sample_are_partial_not_vetoes(self):
        case = strong()
        case["ocr"]["candidate"]["samples"] = [sample("123.com register", 1, .25)]
        result = p.score_case(case)
        self.assertAlmostEqual(result["families"]["P"]["points"], 25 * .5 / 3)
        self.assertEqual(result["families"]["P"]["coverage"]["available"], 1)
        self.assertEqual(result["decision"], "experimental_ad_candidate")

    def test_fuzzy_typo_diacritics_variants_and_gibberish(self):
        typo = p.fuzzy_quality(p.normalize("depost"), "promotion")["quality"]
        self.assertGreater(typo, 0)
        self.assertLess(typo, 1)
        self.assertEqual(p.fuzzy_quality(p.normalize("TRADUÇÃO"), "programme")["quality"], 1)
        self.assertEqual(p.fuzzy_quality("xqz jjj kkk", "promotion")["quality"], 0)
        self.assertEqual(p.fuzzy_quality("xqz jjj kkk", "programme")["quality"], 0)

    def test_domain_and_semantics_are_additive_not_and(self):
        case = strong()
        case["ocr"]["candidate"]["samples"] = [sample("123.com", t) for t in (1, 2, 3)]
        self.assertAlmostEqual(p.text_score(case)["points"], 25 * .65)
        case["ocr"]["candidate"]["samples"] = [sample("deposit", t) for t in (1, 2, 3)]
        self.assertAlmostEqual(p.text_score(case)["points"], 25 * .35)

    def test_outside_domain_reduces_novelty_without_exclusion(self):
        case = strong()
        case["ocr"]["before"] = {"samples": [sample("123.com", 0)]}
        result = p.text_score(case)
        self.assertGreater(result["points"], 0)
        self.assertLess(result["points"], 25)
        self.assertEqual(result["outside_domains"], ["123.com"])
        self.assertGreater(result["mean_qualities"]["domain"], 0)
        case["ocr"]["before"]["samples"][0]["observations"][0]["confidence"] = .25
        low_confidence_outside = p.text_score(case)["points"]
        self.assertGreater(low_confidence_outside, result["points"])
        self.assertLess(low_confidence_outside, 25)

    def test_programme_negative_is_gradual_and_never_an_absolute_veto(self):
        case = strong()
        previous_score = p.score_case(case)["score"]
        for i, frame in enumerate(case["ocr"]["candidate"]["samples"]):
            frame["observations"].append({"text": "ELENCO", "confidence": 1})
            result = p.score_case(case)
            self.assertAlmostEqual(result["soft_negative"]["programme"], 40 * (i + 1) / 3)
            self.assertLessEqual(result["score"], previous_score)
            previous_score = result["score"]
            self.assertEqual(result["decision"], "experimental_ad_candidate")
        self.assertAlmostEqual(result["score"], 85)
        self.assertEqual(p.fuzzy_quality("gambling credits", "programme")["quality"], 0)

    def test_audio_gain_is_monotonic_one_sided_and_floor_is_missing(self):
        case = strong()
        values = []
        for gain in (0, 3, 6, 12):
            case["inputs"]["candidate"]["audio"]["rms_pcm16"] = 100 * 10 ** (gain / 20)
            values.append(p.audio_score(case)["points"])
        self.assertEqual(values, sorted(values))
        self.assertAlmostEqual(values[2], 12.5)
        case["inputs"]["after"].pop("audio")
        result = p.audio_score(case)
        self.assertAlmostEqual(result["points"], 25)
        self.assertEqual(result["coverage"], {"available": 1, "expected": 2})
        case["inputs"]["before"]["audio"]["peak_pcm16"] = 1
        self.assertEqual(p.audio_score(case)["points"], 0)

    def test_static_scene_still_has_return_and_cut_evidence(self):
        case = strong()
        for role in ("before", "after"):
            case["inputs"][role]["visual"]["native_adjacent_mae_p90"] = 0
        result = p.scene_score(case)
        self.assertAlmostEqual(result["points"], 25)
        self.assertEqual(result["parts"]["temporal"], 0)
        self.assertEqual(result["parts"]["return"], 20)
        case["bridge_only_S"]["joined_edge_mae"] = .1
        self.assertLess(p.scene_score(case)["points"], 25)
        self.assertGreater(p.scene_score(case)["points"], 0)

    def test_identical_triplet_has_zero_scene_points_without_gating_other_cues(self):
        for native_p90 in (0, .01):
            case = strong()
            case["bridge_only_S"].update(joined_edge_mae=0, start_seam_mae=0, end_seam_mae=0)
            for role in ("before", "after"):
                case["inputs"][role]["visual"]["native_adjacent_mae_p90"] = native_p90
            result = p.score_case(case)
            with self.subTest(native_p90=native_p90):
                self.assertEqual(result["families"]["S"]["points"], 0)
                for family in ("A", "P"):
                    self.assertAlmostEqual(result["families"][family]["points"], p.SETTINGS["weights"][family])
                self.assertAlmostEqual(result["score"], 85)
                self.assertEqual(result["decision"], "experimental_ad_candidate")
                self.assertEqual(result["action"], "preserve")

    def test_duplicate_boxes_and_duplicate_sample_time_add_no_credit(self):
        case = strong()
        one = sample("123.com register", 1)
        case["ocr"]["candidate"]["samples"] = [one]
        baseline = p.text_score(case)["points"]
        one["observations"] *= 5
        case["ocr"]["candidate"]["samples"] *= 3
        result = p.text_score(case)
        self.assertAlmostEqual(result["points"], baseline)
        self.assertEqual(result["coverage"]["available"], 1)

    def test_central_large_text_beats_same_text_in_edge_banner(self):
        central = strong()
        remove(central, "S")
        remove(central, "A")
        edge = copy.deepcopy(central)
        for frame in edge["ocr"]["candidate"]["samples"]:
            frame["observations"][0]["box"] = [.1, .02, .8, .08]
        good, banner = p.score_case(central), p.score_case(edge)
        self.assertEqual(good["families"]["C"]["L"], 1)
        self.assertEqual(good["soft_negative"]["edge_overlay"], 0)
        self.assertEqual(banner["families"]["C"]["L"], 0)
        self.assertAlmostEqual(banner["soft_negative"]["edge_overlay"], 20)
        self.assertAlmostEqual(banner["score"], 5)
        self.assertEqual(good["decision"], "experimental_ad_candidate")
        self.assertEqual(banner["decision"], "unknown")

    def test_layout_confidence_height_and_partial_overlap_are_continuous(self):
        variants = [(1, [.2, .3, .2, .12], 1), (.25, [.2, .3, .2, .12], .5),
                    (1, [.2, .3, .2, .06], .5), (1, [.1, .3, .2, .12], .5),
                    (1, [.15, .3, .2, .12], .75)]
        for confidence, box, expected in variants:
            case = strong()
            for frame in case["ocr"]["candidate"]["samples"]:
                frame["observations"][0].update(confidence=confidence, box=box)
            result = p.score_case(case)
            self.assertAlmostEqual(result["families"]["C"]["L"], expected)
        case["ocr"]["candidate"]["samples"] = case["ocr"]["candidate"]["samples"][:1]
        self.assertAlmostEqual(p.score_case(case)["families"]["C"]["L"], .75 / 3)

    def test_layout_uses_ordinary_text_but_subtitles_are_not_commercial_banners(self):
        case = strong()
        for frame in case["ocr"]["candidate"]["samples"]:
            frame["observations"][0]["text"] = "HELLO"
        result = p.score_case(case)
        self.assertEqual(result["families"]["P"]["points"], 0)
        self.assertEqual(result["families"]["C"]["L"], 1)
        for frame in case["ocr"]["candidate"]["samples"]:
            frame["observations"][0].update(text="字幕製作", box=[.1, .85, .8, .1])
        result = p.score_case(case)
        self.assertEqual(result["soft_negative"]["edge_overlay"], 0)
        self.assertEqual(result["families"]["C"]["frames"][0]["commercial_weighted_union_area"], 0)
        self.assertEqual(result["soft_negative"]["programme"], 40)

    def test_max_weight_rectangle_union_has_exact_overlap_and_edge_area(self):
        rectangles = [((0, 0, .5, .5), 1), ((.25, 0, .75, .5), .5)]
        self.assertAlmostEqual(p.weighted_union(rectangles), .3125)
        self.assertAlmostEqual(p.weighted_union(rectangles, (0, 0, 1, .2)), .125)
        self.assertAlmostEqual(p.weighted_union(rectangles * 4), .3125)
        case = strong()
        for frame in case["ocr"]["candidate"]["samples"]:
            frame["observations"] = [{"text": "123.com", "confidence": 1, "box": [0, 0, .5, .5]},
                                     {"text": "456.com", "confidence": .25, "box": [.25, 0, .5, .5]}]
        layout = p.score_case(case)["families"]["C"]["frames"][0]
        self.assertAlmostEqual(layout["commercial_weighted_union_area"], .3125)
        self.assertAlmostEqual(layout["commercial_edge_weighted_union_area"], .125)
        self.assertAlmostEqual(layout["e_t"], .4)

    def test_blank_text_with_valid_central_geometry_contributes_nothing(self):
        for text in ("", "   ", "\t\n", "\u3000\u00a0"):
            case = strong()
            remove(case, "S")
            remove(case, "A")
            for frame in case["ocr"]["candidate"]["samples"]:
                frame["observations"][0]["text"] = text
            result = p.score_case(case)
            with self.subTest(text=repr(text)):
                self.assertEqual(result["families"]["P"]["points"], 0)
                self.assertEqual(result["families"]["C"]["L"], 0)
                self.assertEqual(result["families"]["C"]["points"], 0)
                self.assertEqual(result["soft_negative"]["edge_overlay"], 0)
                self.assertEqual(result["score"], 0)

    def test_commercial_union_uses_confidence_and_squared_fuzzy_semantics(self):
        case = strong()
        for frame in case["ocr"]["candidate"]["samples"]:
            frame["observations"][0].update(text="depost", confidence=.25, box=[.1, 0, .5, .1])
        result = p.score_case(case)
        layout = result["families"]["C"]["frames"][0]
        # deposit -> depost has one edit among seven characters: soft quality 5/7.
        self.assertAlmostEqual(layout["commercial_weighted_union_area"], .05 * .5 * (5 / 7) ** 2)
        self.assertAlmostEqual(layout["p_t"], .35 * .5 * (5 / 7))
        self.assertAlmostEqual(result["soft_negative"]["edge_overlay"], 2.5)

    def test_geometry_missing_or_invalid_preserves_original_text_points(self):
        invalid = [None, [0, 0, 1], [True, .3, .2, .2], [.2, .3, float("nan"), .2],
                   [.2, .3, float("inf"), .2], [0, 0, 1e-300, 1e-300], [.2, .3, -.2, .2], [-2, .3, .2, .2]]
        for box in invalid:
            case = strong()
            for frame in case["ocr"]["candidate"]["samples"]:
                frame["observations"][0]["box"] = box
            result = p.score_case(case)
            with self.subTest(box=box):
                self.assertEqual(result["families"]["P"]["points"], 25)
                self.assertEqual(result["families"]["C"]["L"], 0)
                self.assertEqual(result["families"]["C"]["points"], 25)
                self.assertEqual(result["soft_negative"]["edge_overlay"], 0)
                self.assertTrue(result["families"]["C"]["missing_reasons"])
        self.assertEqual(p.image_box([-.1, .3, .4, .12]), (0, .3, .30000000000000004, .42))

    def test_duplicate_geometry_and_sample_times_do_not_inflate_layout_or_penalty(self):
        case = strong()
        one = sample("123.com register", 1)
        one["observations"][0]["box"] = [.1, .1, .8, .2]
        case["ocr"]["candidate"]["samples"] = [one]
        baseline = p.score_case(case)
        one["observations"] *= 4
        case["ocr"]["candidate"]["samples"] *= 3
        repeated = p.score_case(case)
        for key in ("L", "points", "overlay_negative"):
            self.assertEqual(baseline["families"]["C"][key], repeated["families"]["C"][key])
        self.assertEqual(baseline["score"], repeated["score"])

    def test_annotations_paths_old_flags_and_exact_match_do_not_enter_score(self):
        before = strong()
        after = copy.deepcopy(before)
        after.update(id="known-ad", annotations={"groundtruth": "ad", "baseline_exact_match": True})
        after["inputs"]["candidate"]["path"] = "/known/ad/url/creative.mp4"
        after["bridge_only_S"]["status"] = "unknown"
        after["style_W"] = {"status": "unknown"}
        after["ocr"]["candidate"]["status"] = "unknown"
        for frame in after["ocr"]["candidate"]["samples"]:
            frame["matches"] = []
        a, b = p.score_case(before), p.score_case(after)
        self.assertEqual(a["score"], b["score"])
        self.assertEqual(a["families"], b["families"])
        after["inputs"]["candidate"]["status"] = "unknown"
        result = p.score_case(after)
        self.assertGreaterEqual(result["score"], 55)
        self.assertEqual(result["decision"], "unknown")
        self.assertFalse(result["candidate_source_gate"]["valid"])

    def test_missing_source_integrity_gate_preserves_even_with_high_score(self):
        case = strong()
        case.pop("candidate_gate")
        result = p.score_case(case)
        self.assertGreaterEqual(result["score"], 55)
        self.assertFalse(result["candidate_source_gate"]["valid"])
        self.assertEqual(result["decision"], "unknown")
        self.assertEqual(result["action"], "preserve")

    def test_cli_new_directory_source_preservation_settings_and_sha(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "raw.json"
            raw = json.dumps({"policy": "experimental-v1", "cases": [strong()]}).encode()
            source.write_bytes(raw)
            with patch("builtins.print"):
                self.assertEqual(p.main(["--report", str(source), "--work-dir", str(root / "new")]), 0)
            result = json.loads((root / "new/report.json").read_text())
            self.assertEqual(result["source_input_sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(result["settings"]["weights"], {"S": 40, "A": 25, "P": 25})
            self.assertIn("promotion_latin", result["lexicon"])
            self.assertTrue((root / "new/review.csv").is_file())
            with self.assertRaises(FileExistsError):
                p.main(["--report", str(source), "--work-dir", str(root / "new")])
            self.assertEqual(source.read_bytes(), raw)


if __name__ == "__main__":
    unittest.main()
