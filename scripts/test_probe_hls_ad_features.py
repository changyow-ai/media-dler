#!/usr/bin/env python3
"""Focused contract and abstention tests; no fixture labels or network inputs."""
import copy
import importlib.util
import json
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("features", Path(__file__).with_name("probe-hls-ad-features.py"))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
BOUNDARIES = {"start": "discontinuity", "end": "discontinuity"}


def frame(value):
    return bytes([value]) * p.FRAME_BYTES


def media(values, role="before", rms=100, peak=200):
    frames = [frame(v) for v in values]
    times = [i / 10 for i in range(len(values))]
    return {"frames": frames, "times": times, "public": {
        "visual": p.visual_summary(frames, times, role),
        "audio": {"status": "ok", "rms_pcm16": rms, "peak_pcm16": peak, "numerical_floor": peak <= 1}}}


def bridge_media():
    return {"before": media([48, 49, 50]), "candidate": media([220, 220, 220], "candidate"),
            "after": media([51, 52, 53], "after")}


def sample(text, time=1, confidence=.95):
    value = {"status": "ok", "time_seconds": time, "observations": [
        {"text": text, "confidence": confidence, "box": [.1, .2, .5, .1]}]}
    value["matches"] = p.matched_observations(value)
    return value


def ocr(texts, outside=""):
    return {"candidate": {"status": "ok", "samples": [sample(t, i) for i, t in enumerate(texts)]},
            "before": {"status": "ok", "samples": [sample(outside)]},
            "after": {"status": "ok", "samples": [sample(outside)]}}


class PolicyTest(unittest.TestCase):
    def test_real_edges_bridge_and_shot_return_alone_abstains(self):
        s = p.bridge_evidence(bridge_media(), BOUNDARIES)
        self.assertEqual(s["status"], "strong")
        promo = p.promotional_evidence(ocr(["normal scene"] * 3))
        self.assertEqual(p.decide(s, promo, True), "unknown")

    def test_arbitrary_similar_internal_frames_cannot_replace_true_edges(self):
        clips = bridge_media()
        clips["before"] = media([50, 51, 180])
        clips["after"] = media([0, 50, 51], "after")
        result = p.bridge_evidence(clips, BOUNDARIES)
        self.assertAlmostEqual(result["joined_edge_mae"], 180 / 255)
        self.assertEqual(result["status"], "unknown")

    def test_static_black_context_is_low_information(self):
        clips = {"before": media([0, 0, 0]), "candidate": media([200, 200, 200]),
                 "after": media([0, 0, 0], "after")}
        self.assertEqual(p.bridge_evidence(clips, BOUNDARIES)["reason"], "static_or_low_information_context")
        self.assertEqual(clips["before"]["public"]["visual"]["black_pixel_fraction_median"], 1)

    def test_native_baseline_uses_edge_second(self):
        frames = [frame(0), frame(255)] + [frame(100 + i % 2) for i in range(20)]
        visual = p.visual_summary(frames, [i / 10 for i in range(22)], "before")
        self.assertAlmostEqual(visual["native_adjacent_mae_p90"], 1 / 255)
        self.assertEqual(visual["native_adjacent_count"], 10)

    def test_summary_defaults_to_unchanged_full_diagnostics(self):
        frames, times = [frame(0), frame(10), frame(20)], [0, .5, 1]
        default = p.visual_summary(frames, times, "candidate")
        self.assertEqual(default, p.visual_summary(frames, times, "candidate", summary_mode="full"))
        self.assertEqual(set(default), {"frame_count", "sample_count", "quantization_bins_per_channel",
            "top_color_bin_median", "black_pixel_fraction_median", "adjacent_mae_median", "near_static",
            "native_edge_window_seconds", "native_adjacent_count", "native_adjacent_mae_p90"})

    def test_weighted_summary_keeps_exact_edge_pairs_and_skips_discarded_maes(self):
        # Irregular timestamps include both the exact one-second boundary and
        # nearby pairs on either side; an arbitrary first/last N frames is wrong.
        for times, expected in (([0, .125, .75, 1, 1.125, 1.75, 2],
                                 {"before": [3, 4, 5], "after": [0, 1, 2]}),
                                ([3.25, 3.375, 4, 4.25, 4.375, 5, 5.25],
                                 {"before": [3, 4, 5], "after": [0, 1, 2]}),
                                ([0, .999999, 1, 1.000001, 1.5, 2],
                                 {"before": [2, 3, 4], "after": [0, 1]}),
                                ([0, 2], {"before": [], "after": []})):
            frames = [frame(i * i * 5) for i in range(len(times))]
            for role in ("before", "after"):
                with self.subTest(times=times, role=role):
                    with patch.object(p, "mae", wraps=p.mae) as full_mae:
                        full = p.visual_summary(frames, times, role)
                    with patch.object(p, "mae", wraps=p.mae) as minimal_mae, \
                         patch.object(p, "indices") as diagnostic_sampling:
                        minimal = p.visual_summary(frames, times, role, summary_mode="weighted_decision")
                    self.assertEqual(full_mae.call_count, len(frames) - 1)
                    self.assertEqual([call.args for call in minimal_mae.call_args_list],
                                     [(frames[i], frames[i + 1]) for i in expected[role]])
                    diagnostic_sampling.assert_not_called()
                    for field in ("frame_count", "native_edge_window_seconds", "native_adjacent_count",
                                  "native_adjacent_mae_p90"):
                        self.assertEqual(minimal[field], full[field])
                    self.assertEqual(minimal["summary_profile"], "weighted_decision")
                    self.assertEqual(set(minimal["omitted_diagnostics"]), set(full) - set(minimal))

    def test_weighted_candidate_omits_all_diagnostic_maes_explicitly(self):
        frames, times = [frame(10), frame(20), frame(30)], [0, .5, 1]
        full = p.visual_summary(frames, times, "candidate")
        with patch.object(p, "mae") as mae, patch.object(p, "indices") as diagnostic_sampling:
            minimal = p.visual_summary(frames, times, "candidate", summary_mode="weighted_decision")
        mae.assert_not_called()
        diagnostic_sampling.assert_not_called()
        self.assertEqual(minimal["frame_count"], len(frames))
        self.assertEqual(minimal["summary_profile"], "weighted_decision")
        self.assertEqual(set(minimal["omitted_diagnostics"]), set(full) - {"frame_count"})
        self.assertTrue(set(minimal["omitted_diagnostics"]).isdisjoint(minimal))

    def test_invalid_summary_mode_rejected_before_decode(self):
        for mode in (None, "minimal", True, [], {}):
            with self.subTest(mode=mode), patch.object(p, "run") as decode:
                with self.assertRaisesRegex(p.Unknown, "invalid_summary_mode"):
                    p.load_slice({"path": "unused.mp4"}, "candidate", Path("/tmp"), "ffmpeg", "ffprobe",
                                 summary_mode=mode)
                with self.assertRaisesRegex(p.Unknown, "invalid_summary_mode"):
                    p.visual_summary([frame(10), frame(20)], [0, 1], "before", summary_mode=mode)
                decode.assert_not_called()

    def test_missing_eof_context_never_compensates(self):
        clips = bridge_media()
        clips["after"] = None
        self.assertEqual(p.bridge_evidence(clips, BOUNDARIES)["status"], "unknown")
        self.assertEqual(p.bridge_evidence(bridge_media(), dict(BOUNDARIES, end="playlist_end"))["status"], "unknown")

    def test_persistence_counts_times_not_duplicate_boxes(self):
        value = ocr(["888 . COM register", "empty", "empty"])
        value["candidate"]["samples"][0]["matches"] *= 3
        self.assertEqual(p.promotional_evidence(value)["status"], "unknown")
        value["candidate"]["samples"][1] = sample("888.com", 0)
        self.assertEqual(p.promotional_evidence(value)["status"], "unknown")
        value["candidate"]["samples"][1]["time_seconds"] = 2
        self.assertEqual(p.promotional_evidence(value)["status"], "strong")

    def test_persistent_watermark_already_outside_is_not_new(self):
        result = p.promotional_evidence(ocr(["x.vip register"] * 3, "x.vip"))
        self.assertEqual(result["status"], "unknown")
        self.assertEqual(result["outside_domains"], ["x.vip"])

    def test_generic_domain_requires_promotion_and_confidence(self):
        self.assertEqual(p.promotional_evidence(ocr(["news.example.net"] * 3))["status"], "unknown")
        self.assertEqual(p.promotional_evidence(ocr(["12345.cc 注册"] * 3))["status"], "strong")
        value = ocr(["x.app deposit"] * 3)
        value["candidate"]["samples"] = [sample("x.app deposit", i, .79) for i in range(3)]
        self.assertEqual(p.promotional_evidence(value)["status"], "unknown")

    def test_programme_credits_retain_even_with_domain_and_flat_color(self):
        promotion = p.promotional_evidence(ocr(["123.com deposit ELENCO"] * 3))
        clips = bridge_media()
        self.assertEqual(p.decide(p.bridge_evidence(clips, BOUNDARIES), promotion, True), "retain_programme")
        self.assertEqual(clips["candidate"]["public"]["visual"]["top_color_bin_median"], 1)
        no_veto = p.promotional_evidence(ocr(["123.com gambling credits casting"] * 3))
        self.assertFalse(no_veto["programme_contradictions"])

    def test_audio_numerical_floor_has_no_meaningful_gain(self):
        clips = bridge_media()
        clips["before"] = media([48, 49, 50], rms=1, peak=1)
        result = p.style_evidence(clips)
        self.assertEqual(result["relative_audio"]["before"]["status"], "unknown")
        self.assertFalse(result["votes_in_decision"])
        self.assertTrue(p.audio_summary(b"\x01\x00\x00\x00")["numerical_floor"])

    def test_ocr_provider_error_and_outside_failure_abstain(self):
        with self.assertRaises(p.Unknown):
            p.validate_ocr({"engine": "fake", "observations": [], "error": "failed"})
        with patch.object(p, "run", side_effect=p.Unknown("ocr_extract_failed")):
            failed = p.extract_ocr({"path": Path("a.mp4"), "start": 0, "duration": 1, "origin": 0, "times": [.1, .5, .9]}, "candidate", "ffmpeg", None)
        self.assertEqual(failed["status"], "unknown")
        self.assertEqual(failed["samples"][0]["reason"], "ocr_extract_failed")
        value = ocr(["123.com deposit"] * 3)
        value["before"]["status"] = "unknown"
        self.assertEqual(p.promotional_evidence(value)["status"], "unknown")

    def test_batch_duplicate_indices_keep_original_sample_list_and_provider_shapes(self):
        media = {"path": Path("unused.ts"), "start": 0, "duration": 1, "origin": 0, "times": [0, 1]}
        response = {"engine": "test", "observations": [{"text": "example.com deposit", "confidence": .95,
                                                     "box": [.1, .2, .7, .1]}]}
        with patch.object(p, "extract_png_batch", return_value={0: b"first", 1: b"last"}) as batch, \
             patch.object(p, "ocr_png", return_value=response) as provider:
            result = p.extract_ocr(media, "candidate", "ffmpeg", None, batch=True)
        self.assertEqual(batch.call_args.args[1], [0, 0, 1])
        self.assertEqual(result["batch_extraction"]["unique_frame_count"], 2)
        self.assertEqual([s["native_frame_index"] for s in result["samples"]], [0, 0, 1])
        self.assertEqual([s["time_seconds"] for s in result["samples"]], [0, 0, 1])
        self.assertEqual([call.args[0] for call in provider.call_args_list], [b"first", b"first", b"last"])
        self.assertTrue(all(s["observations"] == response["observations"] and s["matches"] for s in result["samples"]))

    def test_failed_batch_falls_back_without_hiding_original_sample_failure(self):
        media = {"path": Path("unused.ts"), "start": 0, "duration": 1, "origin": 0, "times": [.1, .5, .9]}
        with patch.object(p, "extract_png_batch", side_effect=p.Unknown("batch_decode_failed")), \
             patch.object(p, "extract_png", side_effect=[b"one", p.Unknown("original_sample_failed"), b"three"]) as original, \
             patch.object(p, "ocr_png", return_value={"engine": "test", "observations": []}):
            result = p.extract_ocr(media, "candidate", "ffmpeg", None, batch=True)
        self.assertEqual(original.call_count, 3)
        self.assertEqual(result["batch_extraction"], {"status": "fallback", "reason": "batch_decode_failed", "unique_frame_count": 3})
        self.assertEqual([s["status"] for s in result["samples"]], ["ok", "unknown", "ok"])
        self.assertEqual(result["samples"][1]["reason"], "original_sample_failed")
        self.assertEqual(result["status"], "unknown")

    def test_front_only_ocr_calls_provider_once_and_keeps_width_through_fallback(self):
        media = {"path": Path("unused.ts"), "start": 2, "duration": 1, "origin": 0,
                 "times": [.1, .5, .9]}
        for batch, fallback in ((False, False), (True, False), (True, True)):
            with self.subTest(batch=batch, fallback=fallback), \
                 patch.object(p, "extract_png_batch", return_value={0: b"front"},
                              side_effect=p.Unknown("batch_decode_failed") if fallback else None) as batched, \
                 patch.object(p, "extract_png", return_value=b"front") as single, \
                 patch.object(p, "ocr_png", return_value={"engine": "test", "observations": []}) as provider:
                result = p.extract_ocr(media, "candidate", "ffmpeg", None, batch=batch,
                                       fractions=[.1], max_width=960)
            self.assertEqual(result["status"], "ok")
            self.assertEqual(len(result["samples"]), 1)
            self.assertEqual(result["samples"][0]["fraction"], .1)
            self.assertEqual(result["samples"][0]["native_frame_index"], 0)
            self.assertAlmostEqual(result["samples"][0]["time_seconds"], 2.1)
            provider.assert_called_once_with(b"front", None)
            if batch:
                batched.assert_called_once_with(media, [0], "ffmpeg", max_width=960)
                self.assertEqual(result["batch_extraction"]["status"], "fallback" if fallback else "ok")
            else:
                batched.assert_not_called()
            if fallback or not batch:
                single.assert_called_once_with(media, 0, "ffmpeg", max_width=960)
            else:
                single.assert_not_called()

    def test_optional_sampling_preserves_historical_candidate_and_context_defaults(self):
        media = {"path": Path("unused.ts"), "start": 0, "duration": 1, "origin": 0,
                 "times": [.1, .5, .9]}
        for role, fractions in (("candidate", [.1, .5, .9]), ("before", [.5]), ("after", [.5])):
            with self.subTest(role=role), patch.object(p, "extract_png", return_value=b"png") as png, \
                 patch.object(p, "ocr_png", return_value={"engine": "test", "observations": []}) as provider:
                result = p.extract_ocr(media, role, "ffmpeg", None)
            self.assertEqual([sample["fraction"] for sample in result["samples"]], fractions)
            self.assertEqual(provider.call_count, len(fractions))
            self.assertTrue(all(not call.kwargs for call in png.call_args_list))
        self.assertEqual(p.POLICY_SETTINGS["ocr_max_width"], 1920)

    def test_single_front_promotion_cannot_satisfy_temporal_persistence(self):
        value = ocr(["example.com register"])
        result = p.promotional_evidence(value)
        self.assertEqual(p.POLICY_SETTINGS["ocr_min_distinct_times"], 2)
        self.assertEqual(result["domain_distinct_time_counts"], {"example.com": 1})
        self.assertEqual(result["persistent_new_domains"], [])
        self.assertEqual(result["status"], "unknown")

    def test_invalid_explicit_sampling_is_rejected_before_provider_or_decode(self):
        media = {"path": Path("unused.ts"), "start": 0, "duration": 1, "origin": 0, "times": [.1]}
        for options in ({"fractions": []}, {"fractions": [True]}, {"fractions": [float("nan")]},
                        {"fractions": [1.1]}, {"max_width": 0}, {"max_width": True},
                        {"max_width": 960.5}, {"max_width": 1921}):
            with self.subTest(options=options), patch.object(p, "run") as decode, \
                 patch.object(p, "ocr_png") as provider, self.assertRaises(p.Unknown):
                p.extract_ocr(media, "candidate", "ffmpeg", None, **options)
            decode.assert_not_called()
            provider.assert_not_called()

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "requires host FFmpeg")
    def test_explicit_960_png_cap_matches_batch_without_upscaling_or_changing_default(self):
        with tempfile.TemporaryDirectory() as directory:
            for width, height in ((2048, 1152), (320, 180)):
                with self.subTest(source_width=width):
                    clip = Path(directory) / ("%s.mkv" % width)
                    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                                    "testsrc2=size=%sx%s:rate=2:duration=1" % (width, height),
                                    "-c:v", "ffv1", str(clip)], check=True, timeout=20)
                    media = {"path": clip, "start": 0, "duration": 1, "origin": 0, "times": [0, .5]}
                    single = p.extract_png(media, 0, "ffmpeg", max_width=960)
                    batched = p.extract_png_batch(media, [0], "ffmpeg", max_width=960)[0]
                    default = p.extract_png(media, 0, "ffmpeg")
                    self.assertEqual(single, batched)
                    self.assertEqual(struct.unpack(">II", single[16:24]), (min(width, 960), min(height, 540)))
                    self.assertEqual(struct.unpack(">II", default[16:24]), (min(width, 1920), min(height, 1080)))

    def test_missing_or_malformed_batch_outputs_fall_back_and_clean_temporary_files(self):
        media = {"path": Path("unused.ts"), "start": 0, "duration": 1, "origin": 0, "times": [.1, .5, .9]}
        directories = []
        for malformed in (False, True):
            def run(command):
                pattern = Path(command[-1])
                directories.append(pattern.parent)
                self.assertIn("-xerror", command)
                self.assertEqual(command[command.index("-reinit_filter") + 1], "0")
                self.assertNotIn("eval=frame", " ".join(command))
                if malformed:
                    for index in range(1, 4):
                        (pattern.parent / ("frame-%06d.png" % index)).write_bytes(b"invalid png")
            with self.subTest(malformed=malformed), patch.object(p, "run", side_effect=run), \
                 patch.object(p, "extract_png", return_value=b"original png") as original, \
                 patch.object(p, "ocr_png", return_value={"engine": "test", "observations": []}):
                result = p.extract_ocr(media, "candidate", "ffmpeg", None, batch=True)
            self.assertEqual(result["status"], "ok")
            self.assertEqual(result["batch_extraction"]["status"], "fallback")
            self.assertEqual(original.call_count, 3)
        self.assertTrue(all(not directory.exists() for directory in directories))

    def test_cancelled_batch_cleans_temporary_directory_and_does_not_fallback(self):
        media = {"path": Path("unused.ts"), "start": 0, "duration": 1, "origin": 0, "times": [.1, .5, .9]}
        directories = []
        def cancelled(command):
            directories.append(Path(command[-1]).parent)
            raise SystemExit(130)
        with patch.object(p, "run", side_effect=cancelled), patch.object(p, "extract_png") as original:
            with self.assertRaises(SystemExit):
                p.extract_ocr(media, "candidate", "ffmpeg", None, batch=True)
        original.assert_not_called()
        self.assertTrue(all(not directory.exists() for directory in directories))

    def test_both_signals_and_gate_required(self):
        promotion = p.promotional_evidence(ocr(["123.com deposit"] * 3))
        bridge = p.bridge_evidence(bridge_media(), BOUNDARIES)
        self.assertEqual(p.decide(bridge, promotion, True), "experimental_ad_candidate")
        self.assertEqual(p.decide(bridge, promotion, False), "unknown")

    def test_id_annotations_source_names_cannot_enter_decision(self):
        original = {"id": "advertisement", "candidate": {"path": "known-ad.mp4"}, "boundaries": BOUNDARIES,
                    "annotations": {"groundtruth": "ad", "baseline_exact_match": True}}
        changed = copy.deepcopy(original)
        changed.update(id="programme", annotations={"groundtruth": "programme", "baseline_exact_match": False})
        changed["candidate"]["path"] = "ordinary-programme.mp4"
        fake_ocr = ocr(["123.com deposit"] * 3)
        def fake_load(spec, role, *args):
            return bridge_media()[role]
        def fake_extract(clip, role, *args):
            return fake_ocr[role]
        with patch.object(p, "load_slice", side_effect=fake_load), patch.object(p, "extract_ocr", side_effect=fake_extract):
            one = p.process_case(original, Path("/tmp"), "ffmpeg", "ffprobe", None)
            two = p.process_case(changed, Path("/tmp"), "ffmpeg", "ffprobe", None)
        self.assertEqual(one["combined"], two["combined"])
        self.assertEqual(one["content_only_P"], two["content_only_P"])
        self.assertEqual(one["annotations"], original["annotations"])

    def test_partial_and_network_inputs_rejected_before_subprocess(self):
        for spec in ({"path": "https://example.test/a.ts"}, {"path": "a.mp4", "partial": True}):
            with self.subTest(spec=spec), patch.object(p, "run") as run, self.assertRaises(p.Unknown):
                p.load_slice(spec, "candidate", Path("/tmp"), "ffmpeg", "ffprobe")
            run.assert_not_called()

    def test_new_workdir_and_source_preservation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "cases.json"
            original = json.dumps({"schema": 1, "cases": [{"id": "missing", "candidate": {"path": "absent.mp4"}}]})
            source.write_text(original)
            with patch("builtins.print"):
                p.main(["--cases", str(source), "--work-dir", str(root / "new")])
            report = json.loads((root / "new/report.json").read_text())
            self.assertEqual(report["cases"][0]["action"], "preserve")
            self.assertEqual(report["policy_settings"]["bridge_join_factor"], 4)
            self.assertIn("video_presentation_interval", report["policy_settings"]["ocr_sample_interval"])
            self.assertEqual(source.read_text(), original)
            with self.assertRaises(FileExistsError):
                p.main(["--cases", str(source), "--work-dir", str(root / "new")])
            self.assertEqual(source.read_text(), original)

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "requires host FFmpeg")
    def test_small_synthetic_native_decode_and_png_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            clip = root / "clip.mkv"
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=96x64:rate=10",
                            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=8000", "-t", "1",
                            "-c:v", "ffv1", "-c:a", "pcm_s16le", str(clip)], check=True, timeout=20)
            loaded = p.load_slice({"path": clip.name}, "candidate", root, "ffmpeg", "ffprobe")
            self.assertEqual(loaded["public"]["visual"]["frame_count"], 10)
            self.assertEqual(loaded["public"]["audio"]["status"], "ok")
            weighted = p.load_slice({"path": clip.name}, "candidate", root, "ffmpeg", "ffprobe",
                                    summary_mode="weighted_decision")
            self.assertEqual({k: v for k, v in weighted.items() if k != "public"},
                             {k: v for k, v in loaded.items() if k != "public"})
            self.assertEqual({k: v for k, v in weighted["public"].items() if k != "visual"},
                             {k: v for k, v in loaded["public"].items() if k != "visual"})
            self.assertEqual(weighted["public"]["visual"]["frame_count"], 10)
            provider = root / "ocr"
            provider.write_text("#!/usr/bin/env python3\nimport sys,json\nassert sys.stdin.buffer.read().startswith(b'\\x89PNG')\nprint(json.dumps({'engine':'test-provider','observations':[{'text':'123.com deposit','confidence':.99,'box':[0,0,1,1]}]}))\n")
            provider.chmod(0o755)
            evidence = p.extract_ocr(loaded, "candidate", "ffmpeg", str(provider))
            self.assertEqual(evidence["status"], "ok")
            self.assertEqual(len(evidence["samples"]), 3)
            self.assertEqual(evidence["samples"][1]["matches"][0]["domains"], ["123.com"])
            self.assertEqual([s["native_frame_index"] for s in evidence["samples"]], [1, 5, 9])
            self.assertEqual(p.extract_ocr(weighted, "candidate", "ffmpeg", str(provider)), evidence)
            with patch.object(p, "ocr_png", return_value={"engine": "test", "observations": []}) as old_provider:
                old = p.extract_ocr(loaded, "candidate", "ffmpeg", None)
            with patch.object(p, "ocr_png", return_value={"engine": "test", "observations": []}) as batch_provider:
                batch = p.extract_ocr(loaded, "candidate", "ffmpeg", None, batch=True)
            self.assertEqual([call.args[0] for call in old_provider.call_args_list],
                             [call.args[0] for call in batch_provider.call_args_list])
            self.assertEqual(old["samples"], batch["samples"])
            self.assertEqual(batch["batch_extraction"]["status"], "ok")
            with self.assertRaises(p.Unknown):
                p.load_slice({"path": clip.name, "duration_seconds": 2}, "candidate", root, "ffmpeg", "ffprobe")


    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "requires host FFmpeg")
    def test_resolution_change_preserves_frames_and_emits_one_png_per_sample(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, size in enumerate(("128x72", "256x144")):
                subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                                "testsrc2=s=%s:r=10:d=2" % size, "-c:v", "libx264", "-bf", "0",
                                "-f", "mpegts", str(root / (str(index) + ".ts"))], check=True, timeout=20)
            playlist = root / "list.m3u8"
            playlist.write_text("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n0.ts\n"
                                "#EXT-X-DISCONTINUITY\n#EXTINF:2,\n1.ts\n#EXT-X-ENDLIST\n")
            target = root / "changed.ts"
            subprocess.run(["ffmpeg", "-v", "error", "-protocol_whitelist", "file", "-i", str(playlist),
                            "-c", "copy", str(target)], check=True, timeout=20)
            loaded = p.load_slice({"path": str(target)}, "candidate", root, "ffmpeg", "ffprobe")
            self.assertEqual(len(loaded["frames"]), 40)
            images = []
            def provider(png, command):
                images.append(png)
                return {"engine": "test", "observations": []}
            with patch.object(p, "ocr_png", side_effect=provider):
                evidence = p.extract_ocr(loaded, "candidate", "ffmpeg", None)
            self.assertEqual(evidence["status"], "ok")
            self.assertEqual(len(images), 3)
            self.assertEqual([png.count(b"\x89PNG\r\n\x1a\n") for png in images], [1, 1, 1])
            self.assertGreater(evidence["samples"][-1]["time_seconds"], 3)
            with patch.object(p, "ocr_png", return_value={"engine": "test", "observations": []}) as batched:
                batch = p.extract_ocr(loaded, "candidate", "ffmpeg", None, batch=True)
            self.assertEqual(images, [call.args[0] for call in batched.call_args_list])
            self.assertEqual(evidence["samples"], batch["samples"])
            self.assertEqual(batch["batch_extraction"]["status"], "ok")

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "requires host FFmpeg")
    def test_delayed_video_and_longer_audio_preserve_all_video_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            clip = root / "delayed.mp4"
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                            "testsrc2=size=96x64:rate=10:duration=1", "-f", "lavfi", "-i",
                            "sine=frequency=440:sample_rate=8000:duration=1.6", "-filter_complex",
                            "[0:v]setpts=PTS+0.2/TB[v]", "-map", "[v]", "-map", "1:a",
                            "-fps_mode", "passthrough", "-c:v", "mpeg4", "-c:a", "aac", str(clip)],
                           check=True, timeout=20)
            loaded = p.load_slice({"path": clip.name}, "candidate", root, "ffmpeg", "ffprobe")
            public = loaded["public"]
            native = json.loads(subprocess.check_output([
                "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries",
                "stream=nb_read_frames", "-of", "json", str(clip)], timeout=20))
            self.assertEqual(public["visual"]["frame_count"], int(native["streams"][0]["nb_read_frames"]))
            self.assertGreaterEqual(public["visual"]["frame_count"], 9)
            timestamp_data = json.loads(subprocess.check_output([
                "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "frame=best_effort_timestamp_time", "-of", "json", str(clip)], timeout=20))
            for actual, expected in zip(loaded["times"], timestamp_data["frames"]):
                self.assertAlmostEqual(actual, float(expected["best_effort_timestamp_time"]), places=5)
            self.assertAlmostEqual(public["duration_seconds"], 1.6, places=3)
            coverage = public["video_coverage"]
            self.assertEqual(coverage["basis"], "video_presentation_interval_intersection")
            self.assertAlmostEqual(coverage["expected_start_in_slice_seconds"], .2, places=3)
            self.assertGreaterEqual(coverage["decoded_last_in_slice_seconds"], 1)
            self.assertLess(coverage["expected_end_in_slice_seconds"], public["duration_seconds"])
            with patch.object(p, "ocr_png", return_value={"engine": "test", "observations": []}):
                evidence = p.extract_ocr(loaded, "candidate", "ffmpeg", "fake")
            self.assertEqual(evidence["status"], "ok")
            self.assertEqual([s["fraction"] for s in evidence["samples"]], [.1, .5, .9])
            self.assertTrue(all(s["time_seconds"] == loaded["times"][s["native_frame_index"]]
                                for s in evidence["samples"]))
            self.assertTrue(all(loaded["video_window"][0] < s["time_seconds"] < loaded["video_window"][1]
                                for s in evidence["samples"]))
            # Explicit slices are intersected with video too; an audio-only tail is not a clip.
            sliced = p.load_slice({"path": clip.name, "start_seconds": .7, "duration_seconds": .6},
                                  "candidate", root, "ffmpeg", "ffprobe")
            self.assertLess(sliced["video_window"][1], .6)
            with self.assertRaisesRegex(p.Unknown, "no_video"):
                p.load_slice({"path": clip.name, "start_seconds": 1.3, "duration_seconds": .2},
                             "candidate", root, "ffmpeg", "ffprobe")
            with self.assertRaisesRegex(p.Unknown, "exceeds_media_duration"):
                p.load_slice({"path": clip.name, "duration_seconds": 2}, "candidate", root, "ffmpeg", "ffprobe")

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "requires host FFmpeg")
    def test_h264_b_frames_long_gop_slice_matches_sequential_decode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            clip = root / "long-gop.mp4"
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                            "testsrc2=size=96x64:rate=25:duration=14", "-f", "lavfi", "-i",
                            "sine=frequency=440:sample_rate=8000:duration=14", "-c:v", "libx264",
                            "-g", "150", "-keyint_min", "150", "-sc_threshold", "0", "-bf", "3",
                            "-c:a", "aac", str(clip)], check=True, timeout=30)
            ts = root / "short-context.ts"
            subprocess.run(["ffmpeg", "-v", "error", "-i", str(clip), "-t", "4.17", "-c", "copy",
                            "-f", "mpegts", str(ts)], check=True, timeout=20)
            for source, role, start, duration in ((clip, "candidate", 12.17, 1.31), (ts, "before", 0, None)):
                specification = {"path": source.name, "start_seconds": start}
                if duration is not None:
                    specification["duration_seconds"] = duration
                loaded = p.load_slice(specification, role, root, "ffmpeg", "ffprobe")
                self.assertGreater(loaded["public"]["metadata"]["streams"][0]["has_b_frames"], 0)
                actual_start = loaded["start"]
                actual_duration = loaded["duration"]
                # Independent reference: decode from the beginning, then trim the presentation frames.
                reference = subprocess.run([
                    "ffmpeg", "-v", "error", "-copyts", "-i", str(source), "-map", "0:v:0", "-an", "-vf",
                    "trim=start=%s:end=%s,scale=64:36,setpts=PTS-STARTPTS" %
                    (loaded["origin"] + actual_start, loaded["origin"] + actual_start + actual_duration),
                    "-fps_mode", "passthrough", "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"],
                    check=True, capture_output=True, timeout=30).stdout
                self.assertGreater(len(reference), 20 * p.FRAME_BYTES)
                self.assertEqual(b"".join(loaded["frames"]), reference)
                probed = json.loads(subprocess.check_output([
                    "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                    "frame=best_effort_timestamp_time", "-of", "json", str(source)], timeout=20))
                begin = loaded["origin"] + actual_start
                expected_times = [float(f["best_effort_timestamp_time"]) - begin for f in probed["frames"]
                                  # ffprobe prints six decimal places; avoid binary-float boundary exclusion.
                                  if begin - 1e-7 <= float(f["best_effort_timestamp_time"]) < begin + actual_duration - 1e-7]
                self.assertEqual(len(loaded["times"]), len(expected_times), (source.name, begin, actual_duration, loaded["times"][:2], loaded["times"][-2:], expected_times[:2], expected_times[-2:]))
                for actual, expected in zip(loaded["times"], expected_times):
                    self.assertAlmostEqual(actual, expected, places=5)
                with patch.object(p, "ocr_png", return_value={"engine": "test", "observations": []}):
                    evidence = p.extract_ocr(loaded, role, "ffmpeg", "fake")
                self.assertEqual(evidence["status"], "ok")
                self.assertTrue(all(s["time_seconds"] == actual_start + loaded["times"][s["native_frame_index"]]
                                    for s in evidence["samples"]))
            self.assertGreater(loaded["times"][-1], 1.8)

if __name__ == "__main__":
    unittest.main()
