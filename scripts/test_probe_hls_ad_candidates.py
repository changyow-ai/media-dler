#!/usr/bin/env python3
"""Focused checks for conservative candidate discovery and exact content proof."""
import copy
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("probe", Path(__file__).with_name("probe-hls-ad-candidates.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
BASE = "https://example.test/hls/index.m3u8"


def manifest(body):
    return "#EXTM3U\n#EXT-X-TARGETDURATION:4\n" + body + "\n#EXT-X-ENDLIST\n"


def stream_data():
    return {"streams": [
        {"index": 0, "codec_type": "video", "codec_name": "h264", "width": 1920, "height": 1080,
         "profile": "High", "level": 40, "pix_fmt": "yuv420p", "extradata_hash": "SHA256:" + "a" * 64},
        {"index": 1, "codec_type": "audio", "codec_name": "aac", "profile": "LC", "sample_rate": "44100",
         "channels": 2, "channel_layout": "stereo", "extradata_hash": "SHA256:" + "b" * 64}],
        "packets": [{"stream_index": i, "data_hash": "SHA256:" + h * 64, "pts": t}
                    for i, h, t in [(0, "1", 100), (1, "2", 100), (0, "3", 101), (1, "4", 101)]]}


class ParserTest(unittest.TestCase):
    def test_leading_middle_trailing_without_markers(self):
        regions = probe.parse_manifest(manifest("#EXTINF:4,\na.ts\n#EXT-X-DISCONTINUITY\n"
                                                "#EXTINF:2,\nb.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:3,\nc.ts"), BASE)
        self.assertEqual([r["duration_seconds"] for r in regions], [4, 2, 3])
        self.assertEqual([r["start_boundary"] for r in regions], ["playlist_start", "discontinuity", "discontinuity"])
        self.assertEqual([r["end_boundary"] for r in regions], ["discontinuity", "discontinuity", "playlist_end"])
        self.assertFalse(any(r["provider_marker_hint"] for r in regions))
        self.assertEqual(regions[0]["segments"][0]["url"], "https://example.test/hls/a.ts")

    def test_marker_is_only_hint_on_following_region(self):
        regions = probe.parse_manifest(manifest("#EXTINF:4,\na.ts\n#EXT-X-DISCONTINUITY\n"
                                                "# ppvod-ad-injected v1\n#EXTINF:2,\nb.ts"), BASE)
        self.assertFalse(regions[0]["provider_marker_hint"])
        self.assertTrue(regions[1]["provider_marker_hint"])
        self.assertNotIn("status", regions[1])

    def test_no_discontinuity_is_single_preserved_region(self):
        self.assertEqual(len(probe.parse_manifest(manifest("#EXTINF:4,\na.ts"), BASE)), 1)

    def test_unsupported_or_incomplete_is_rejected(self):
        bodies = ["#EXTINF:4,", "a.ts", "#EXTINF:nan,\na.ts", "#EXTINF:-1,\na.ts",
                  "#EXTINF:4\na.ts", "#EXTINF:4,\n#EXT-X-DISCONTINUITY\na.ts",
                  "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:4,\na.ts",
                  "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\na.ts",
                  "#EXT-X-BYTERANGE:20\n#EXTINF:4,\na.ts",
                  "#EXT-X-STREAM-INF:BANDWIDTH=200\na.m3u8",
                  "#EXT-X-UNKNOWN:1\n#EXTINF:4,\na.ts",
                  "#EXT-X-ENDLIST\n#EXTINF:4,\na.ts",
                  "#EXTINF:4,\nfile:///tmp/a.ts", "#EXTINF:4,\nhttps://u:p@example.test/a.ts",
                  "#EXTINF:4,\nhttps://example.test/a.ts#fragment", "#EXTINF:4,\na.mp4"]
        for body in bodies:
            with self.subTest(body=body), self.assertRaises(probe.Unknown):
                probe.parse_manifest(manifest(body), BASE)
        with self.assertRaises(probe.Unknown):
            probe.parse_manifest("#EXTM3U\n#EXTINF:4,\na.ts\n", BASE)

    def test_limits_only_skip_never_classify(self):
        region = probe.parse_manifest(manifest("#EXTINF:61,\na.ts"), BASE)[0]
        self.assertEqual(probe.budget_reason(region, 60, 128), "duration_budget_exceeded")
        self.assertIsNone(probe.budget_reason(region, 62, 128))
        region["segments"].append(dict(region["segments"][0]))
        self.assertEqual(probe.budget_reason(region, 62, 1), "segment_budget_exceeded")
        region["segments"] = []
        self.assertEqual(probe.budget_reason(region, 62, 1), "empty_region")


class FingerprintTest(unittest.TestCase):
    def fp(self, data):
        with patch.object(probe, "run_json", return_value=data):
            return probe.fingerprint(Path("sample.mp4"), "ffprobe")

    def test_all_packets_match_despite_timestamps_and_interleaving(self):
        original = stream_data()
        changed = copy.deepcopy(original)
        changed["packets"] = [changed["packets"][1], changed["packets"][0], changed["packets"][3], changed["packets"][2]]
        for packet in changed["packets"]:
            packet["pts"] += 5000
        self.assertTrue(probe.same_content(self.fp(original), self.fp(changed)))

    def test_same_first_packet_does_not_hide_changed_tail(self):
        original = stream_data()
        changed = copy.deepcopy(original)
        changed["packets"][2]["data_hash"] = "SHA256:" + "f" * 64
        self.assertFalse(probe.same_content(self.fp(original), self.fp(changed)))

    def test_additional_audio_packet_is_not_equal(self):
        original = stream_data()
        changed = copy.deepcopy(original)
        changed["packets"].append(copy.deepcopy(changed["packets"][1]))
        self.assertFalse(probe.same_content(self.fp(original), self.fp(changed)))

    def test_missing_audio_tail_is_not_equal(self):
        original = stream_data()
        changed = copy.deepcopy(original)
        changed["packets"].pop()
        self.assertFalse(probe.same_content(self.fp(original), self.fp(changed)))

    def test_metadata_change_is_not_equal(self):
        original = stream_data()
        changed = copy.deepcopy(original)
        changed["streams"][0]["width"] = 1280
        self.assertFalse(probe.same_content(self.fp(original), self.fp(changed)))

    def test_unsupported_or_unhashed_streams_are_unknown(self):
        for change in ("codec", "extra", "hash"):
            data = stream_data()
            if change == "codec":
                data["streams"][0]["codec_name"] = "hevc"
            elif change == "extra":
                data["streams"].append({"index": 2, "codec_type": "audio", "codec_name": "aac"})
            else:
                data["packets"][0].pop("data_hash")
            with self.subTest(change=change), self.assertRaises(probe.Unknown):
                self.fp(data)


class DownloadTest(unittest.TestCase):
    class Response(io.BytesIO):
        url = "https://example.test/a.ts"

    def test_reused_url_costs_no_network_and_retains_whole_segment(self):
        with tempfile.TemporaryDirectory() as temp:
            fetcher = probe.Fetcher(Path(temp), {}, 20)
            with patch.object(fetcher.opener, "open", return_value=self.Response(b"payload")) as request:
                first = fetcher.fetch("https://example.test/a.ts", 10)
                second = fetcher.fetch("https://example.test/a.ts", 10)
            self.assertEqual(request.call_count, 1)
            self.assertFalse(first["cache_reused"])
            self.assertTrue(second["cache_reused"])
            self.assertEqual(fetcher.network_bytes, 7)
            self.assertEqual((fetcher.cache_reuse_count, fetcher.cache_reuse_bytes), (1, 7))
            with self.assertRaises(probe.Unknown):
                fetcher.fetch("https://example.test/a.ts", 6)

    def test_oversized_response_stops_at_hard_budget_and_drops_partial_file(self):
        with tempfile.TemporaryDirectory() as temp:
            fetcher = probe.Fetcher(Path(temp), {}, 4)
            with patch.object(fetcher.opener, "open", return_value=self.Response(b"too much data")):
                with self.assertRaises(probe.Unknown):
                    fetcher.fetch("https://example.test/a.ts", 100)
            self.assertEqual(fetcher.network_bytes, 4)
            self.assertFalse(list(Path(temp).iterdir()))
            self.assertFalse(fetcher.cache)

    def test_non_http_redirect_is_rejected(self):
        with self.assertRaises(probe.Unknown):
            probe.SafeRedirect().redirect_request(None, None, 302, "Found", {}, "file:///tmp/private.ts")


class ReportTest(unittest.TestCase):
    def test_marked_short_region_stays_unknown_without_fetch(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "input.m3u8"
            source.write_text(manifest("# ppvod-ad-injected v1\n#EXTINF:4,\na.ts"))
            target = Path(temp) / "out"
            self.assertEqual(probe.main(["--manifest", str(source), "--base-url", BASE, "--work-dir", str(target)]), 0)
            report = json.loads((target / "report.json").read_text())
            region = report["regions"][0]
            self.assertEqual((region["status"], region["action"], region["reason"]), ("unknown", "preserve", "not_fetched"))
            self.assertEqual(report["cost"]["network_bytes"], 0)
            with self.assertRaises(SystemExit):
                probe.main(["--manifest", str(source), "--base-url", BASE, "--work-dir", str(target)])

    def test_fetch_failure_preserves_region_and_continues(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "input.m3u8"
            source.write_text(manifest("#EXTINF:4,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4,\nb.ts"))
            target = Path(temp) / "out"
            with patch.object(probe.Fetcher, "fetch", side_effect=probe.Unknown("budget")):
                self.assertEqual(probe.main(["--manifest", str(source), "--base-url", BASE, "--work-dir", str(target), "--fetch"]), 0)
            report = json.loads((target / "report.json").read_text())
            self.assertTrue(all(r["status"] == "unknown" and r["action"] == "preserve" for r in report["regions"]))
            self.assertEqual(len(report["regions"]), 2)


if __name__ == "__main__":
    unittest.main()
