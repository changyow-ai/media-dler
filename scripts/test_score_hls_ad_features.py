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
    return {"time_seconds": time, "observations": [{"text": text, "confidence": confidence, "box": [0, 0, 1, 1]}]}


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
    elif family == "V":
        case["inputs"]["candidate"]["visual"].pop("top_color_bin_median", None)


class WeightedTest(unittest.TestCase):
    def test_each_family_can_be_missing_and_remaining_can_reach_threshold(self):
        for family in ("S", "A", "P", "V"):
            case = strong()
            remove(case, family)
            result = p.score_case(case)
            with self.subTest(family=family):
                self.assertEqual(result["families"][family]["points"], 0)
                self.assertGreaterEqual(result["score"], 55)
                self.assertEqual(result["decision"], "experimental_ad_candidate")
                self.assertEqual(result["action"], "preserve")
        case = strong()
        remove(case, "S")
        self.assertAlmostEqual(p.score_case(case)["score"], 57)

    def test_no_single_family_reaches_threshold_and_no_renormalization(self):
        for keep in ("S", "A", "P", "V"):
            case = strong()
            for family in ("S", "A", "P", "V"):
                if family != keep:
                    remove(case, family)
            result = p.score_case(case)
            with self.subTest(family=keep):
                self.assertAlmostEqual(result["score"], p.SETTINGS["weights"][keep])
                self.assertEqual(result["decision"], "unknown")

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
        original = p.score_case(case)["score"]
        for i, frame in enumerate(case["ocr"]["candidate"]["samples"]):
            frame["observations"].append({"text": "ELENCO", "confidence": 1})
            result = p.score_case(case)
            self.assertAlmostEqual(result["soft_negative"]["programme"], 40 * (i + 1) / 3)
            self.assertLess(result["score"], original)
            self.assertEqual(result["decision"], "experimental_ad_candidate")
        self.assertAlmostEqual(result["score"], 57)
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
                for family in ("A", "P", "V"):
                    self.assertAlmostEqual(result["families"][family]["points"], p.SETTINGS["weights"][family])
                self.assertAlmostEqual(result["score"], 57)
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
            self.assertEqual(result["settings"]["weights"], {"S": 40, "A": 25, "P": 25, "V": 10})
            self.assertIn("promotion_latin", result["lexicon"])
            self.assertTrue((root / "new/review.csv").is_file())
            with self.assertRaises(FileExistsError):
                p.main(["--report", str(source), "--work-dir", str(root / "new")])
            self.assertEqual(source.read_bytes(), raw)


if __name__ == "__main__":
    unittest.main()
