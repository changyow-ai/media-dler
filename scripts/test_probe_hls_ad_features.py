#!/usr/bin/env python3
"""Focused contract and abstention tests; no fixture labels or network inputs."""
import copy
import importlib.util
import json
import shutil
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
            provider = root / "ocr"
            provider.write_text("#!/usr/bin/env python3\nimport sys,json\nassert sys.stdin.buffer.read().startswith(b'\\x89PNG')\nprint(json.dumps({'engine':'test-provider','observations':[{'text':'123.com deposit','confidence':.99,'box':[0,0,1,1]}]}))\n")
            provider.chmod(0o755)
            evidence = p.extract_ocr(loaded, "candidate", "ffmpeg", str(provider))
            self.assertEqual(evidence["status"], "ok")
            self.assertEqual(len(evidence["samples"]), 3)
            self.assertEqual(evidence["samples"][1]["matches"][0]["domains"], ["123.com"])
            self.assertEqual([s["native_frame_index"] for s in evidence["samples"]], [1, 5, 9])
            with self.assertRaises(p.Unknown):
                p.load_slice({"path": clip.name, "duration_seconds": 2}, "candidate", root, "ffmpeg", "ffprobe")


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
