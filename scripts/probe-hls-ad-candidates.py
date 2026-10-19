#!/usr/bin/env python3
"""Read-only host experiment: discontinuity regions are candidates, never ad proof.

Produces report.json and optional downloaded/remuxed samples in a NEW directory.
Does not write a filtered playlist, modify sources, or authorize production skips.
"""
import argparse
import hashlib
import json
import math
import re
import subprocess
import time
from pathlib import Path
from urllib.parse import urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

SCHEMA = 1
NORMALIZER = "h264-aac-complete-packet-sequences-v1"
METADATA = {"#EXT-X-VERSION", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
            "#EXT-X-DISCONTINUITY-SEQUENCE", "#EXT-X-ALLOW-CACHE"}
ALLOWED = {"#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-KEY:METHOD=NONE", "#EXT-X-PLAYLIST-TYPE:VOD"}
MARKER = "# ppvod-ad-injected v1"


class Unknown(ValueError):
    pass


def http_url(url):
    try:
        p = urlsplit(url)
        return (p.scheme.lower() in ("http", "https") and p.hostname is not None
                and p.username is None and p.password is None and not p.fragment
                and not any(c.isspace() or ord(c) < 32 for c in url) and p.port != 0)
    except ValueError:
        return False


def parse_manifest(text, base_url):
    """Accept only the complete plain-TS VOD subset used by HlsResumeGuard."""
    if not http_url(base_url):
        raise Unknown("invalid_base_url")
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines or lines[0] != "#EXTM3U" or lines[-1] != "#EXT-X-ENDLIST":
        raise Unknown("not_complete_media_vod")
    regions = []
    region = {"index": 0, "start_boundary": "playlist_start", "start_seconds": 0.0,
              "segments": [], "provider_marker_hint": False}
    elapsed = 0.0
    pending = None
    marker = False
    for lineno, line in enumerate(lines[1:], 2):
        if line == "#EXTM3U" or line == "#EXT-X-ENDLIST" and lineno != len(lines):
            raise Unknown("unexpected_playlist_boundary")
        if line.startswith("#EXTINF:"):
            if pending is not None:
                raise Unknown("missing_segment_uri")
            value, sep, _ = line[len("#EXTINF:"):].partition(",")
            try:
                pending = float(value)
            except ValueError:
                raise Unknown("invalid_extinf")
            if not sep or not math.isfinite(pending) or pending <= 0:
                raise Unknown("invalid_extinf")
        elif line in ("#EXT-X-DISCONTINUITY", "#EXT-X-ENDLIST"):
            if pending is not None:
                raise Unknown("missing_segment_uri")
            boundary = "discontinuity" if line == "#EXT-X-DISCONTINUITY" else "playlist_end"
            region["end_boundary"] = boundary
            region["duration_seconds"] = elapsed - region["start_seconds"]
            regions.append(region)
            region = {"index": len(regions), "start_boundary": boundary,
                      "start_seconds": elapsed, "segments": [], "provider_marker_hint": False}
        elif line == MARKER:
            marker = True
        elif line in ALLOWED or line.split(":", 1)[0] in METADATA:
            continue
        elif line.startswith(("#EXT", "#ANVATO", "#UPLYNK")):
            raise Unknown("unsupported_tag_at_line_%d:%s" % (lineno, line.split(":", 1)[0]))
        elif line.startswith("#"):
            continue
        else:
            if pending is None:
                raise Unknown("segment_without_extinf")
            url = urljoin(base_url, line)
            if not http_url(url) or not urlsplit(url).path.lower().endswith(".ts"):
                raise Unknown("invalid_ts_url_at_line_%d" % lineno)
            region["segments"].append({"url": url, "duration_seconds": pending})
            region["provider_marker_hint"] |= marker
            marker = False
            elapsed += pending
            pending = None
    if not any(r["segments"] for r in regions):
        raise Unknown("empty_playlist")
    return regions


def budget_reason(region, max_seconds, max_segments):
    if not region["segments"]:
        return "empty_region"
    if region["duration_seconds"] > max_seconds:
        return "duration_budget_exceeded"
    if len(region["segments"]) > max_segments:
        return "segment_budget_exceeded"
    return None


def run_json(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=120, check=True)
        return json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, ValueError) as exc:
        raise Unknown("probe_failed:%s" % type(exc).__name__)


def fingerprint(path, ffprobe):
    """Hash every complete packet per stream, ignoring timestamps/interleaving."""
    data = run_json([ffprobe, "-v", "error", "-show_streams", "-show_packets",
                     "-show_data_hash", "sha256", "-of", "json", str(path)])
    streams = data.get("streams", [])
    video = [s for s in streams if s.get("codec_type") == "video"]
    audio = [s for s in streams if s.get("codec_type") == "audio"]
    if len(streams) != 2 or len(video) != 1 or len(audio) != 1:
        raise Unknown("requires_exactly_one_video_and_one_audio_stream")
    if video[0].get("codec_name") != "h264" or audio[0].get("codec_name") != "aac":
        raise Unknown("unsupported_codec")
    result = {"normalizer": NORMALIZER, "streams": {}}
    for kind, stream, fields in [
        ("video", video[0], ("codec_name", "profile", "level", "width", "height", "pix_fmt", "extradata_hash")),
        ("audio", audio[0], ("codec_name", "profile", "sample_rate", "channels", "channel_layout", "extradata_hash")),
    ]:
        metadata = {field: stream.get(field) for field in fields}
        if not metadata["extradata_hash"]:
            raise Unknown("missing_codec_extradata_hash")
        hashes = [p.get("data_hash", "") for p in data.get("packets", [])
                  if p.get("stream_index") == stream["index"]]
        if not hashes or any(not re.fullmatch(r"SHA256:[0-9a-fA-F]{64}", h) for h in hashes):
            raise Unknown("missing_packet_hashes")
        canonical = "".join(h.split(":", 1)[1].lower() + "\n" for h in hashes)
        result["streams"][kind] = {"metadata": metadata, "packet_count": len(hashes),
                                   "packet_sequence_sha256": hashlib.sha256(canonical.encode("ascii")).hexdigest()}
    return result


def same_content(candidate, reference):
    # No prefix/suffix trimming, single-packet anchor, or timestamp heuristics.
    return (candidate.get("normalizer") == reference.get("normalizer") == NORMALIZER
            and set(candidate.get("streams", {})) == {"video", "audio"}
            and candidate == reference)


class SafeRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not http_url(newurl):
            raise Unknown("unsafe_redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Fetcher:
    def __init__(self, directory, headers, max_total_bytes):
        self.directory = directory
        self.headers = headers
        self.max_total_bytes = max_total_bytes
        self.network_bytes = 0
        self.cache_reuse_count = 0
        self.cache_reuse_bytes = 0
        self.cache = {}
        self.opener = build_opener(SafeRedirect())

    def fetch(self, url, remaining):
        if url in self.cache:
            item = self.cache[url]
            if item["bytes"] > remaining:
                raise Unknown("candidate_byte_budget_exceeded")
            self.cache_reuse_count += 1
            self.cache_reuse_bytes += item["bytes"]
            return dict(item, cache_reused=True)
        path = self.directory / ("segment-%04d.ts" % len(self.cache))
        size = 0
        digest = hashlib.sha256()
        deadline = time.monotonic() + 30
        try:
            with self.opener.open(Request(url, headers=self.headers), timeout=10) as response, path.open("xb") as output:
                if not http_url(response.url):
                    raise Unknown("unsafe_response_url")
                while True:
                    allowed = min(remaining - size, self.max_total_bytes - self.network_bytes)
                    if allowed <= 0:
                        # Conservatively leave an exact-budget body unknown rather than
                        # reading beyond either hard byte limit to test for EOF.
                        raise Unknown("download_byte_budget_exceeded")
                    chunk = response.read(min(65536, allowed))
                    self.network_bytes += len(chunk)
                    if not chunk:
                        break
                    if len(chunk) > allowed:
                        raise Unknown("download_byte_budget_exceeded")
                    size += len(chunk)
                    digest.update(chunk)
                    output.write(chunk)
                    if time.monotonic() > deadline:
                        raise Unknown("download_elapsed_budget_exceeded")
            if not size:
                raise Unknown("empty_segment")
        except Exception:
            path.unlink(missing_ok=True)
            raise
        item = {"path": str(path.resolve()), "bytes": size, "sha256": digest.hexdigest()}
        self.cache[url] = item
        return dict(item, cache_reused=False)


def remux(region, directory, ffmpeg, ffprobe):
    local = directory / ("candidate-%03d.m3u8" % region["index"])
    output = local.with_suffix(".mp4")
    target = math.ceil(max(s["duration_seconds"] for s in region["segments"]))
    lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:%d" % target, "#EXT-X-MEDIA-SEQUENCE:0"]
    for segment in region["segments"]:
        lines.extend(["#EXTINF:%s," % segment["duration_seconds"], segment["download"]["path"]])
    lines.append("#EXT-X-ENDLIST")
    local.write_text("\n".join(lines) + "\n")
    # Check codecs before remux; -map 0 retains all streams so extras cannot disappear silently.
    probe = run_json([ffprobe, "-v", "error", "-protocol_whitelist", "file", "-show_streams", "-of", "json", str(local)])
    streams = probe.get("streams", [])
    if sorted(s.get("codec_name", "") for s in streams) != ["aac", "h264"]:
        raise Unknown("unsupported_candidate_streams")
    try:
        completed = subprocess.run([ffmpeg, "-nostdin", "-v", "warning", "-n", "-protocol_whitelist", "file", "-i", str(local),
                                    "-map", "0", "-c", "copy", str(output)],
                                   capture_output=True, text=True, timeout=120, check=True)
    except (OSError, subprocess.SubprocessError) as exc:
        raise Unknown("remux_failed:%s" % type(exc).__name__)
    log = output.with_suffix(".log")
    log.write_text(completed.stderr)
    if completed.stderr.strip():
        raise Unknown("remux_warning_requires_review")
    return output


def positive(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError("must be positive and finite")
    return number


def positive_int(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return number


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--reference", action="append", default=[], metavar="LABEL=MP4")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--header", action="append", default=[], metavar="NAME=VALUE")
    parser.add_argument("--max-seconds", type=positive, default=60)
    parser.add_argument("--max-segments", type=positive_int, default=128)
    parser.add_argument("--max-bytes", type=positive_int, default=32 * 1024 * 1024)
    parser.add_argument("--max-total-bytes", type=positive_int, default=128 * 1024 * 1024)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    args = parser.parse_args(argv)
    headers = {}
    for entry in args.header:
        name, sep, value = entry.partition("=")
        if not sep or not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name) or "\n" in value or "\r" in value:
            parser.error("invalid --header; expected NAME=VALUE")
        headers[name] = value
    references = {}
    for entry in args.reference:
        label, sep, path = entry.partition("=")
        if not sep or not label or not path or label in references:
            parser.error("invalid or duplicate --reference; expected LABEL=MP4")
        references[label] = Path(path)
    try:
        args.work_dir.mkdir(parents=True, exist_ok=False)
    except FileExistsError:
        parser.error("--work-dir must not already exist")
    started = time.monotonic()
    report = {"schema": SCHEMA, "normalizer": NORMALIZER, "purpose": "read_only_experiment",
              "policy": "all_regions_preserved; markers_and_duration_are_not_ad_proof",
              "manifest": str(args.manifest.resolve()), "base_url": args.base_url,
              "limits": {"seconds_per_region": args.max_seconds, "segments_per_region": args.max_segments,
                         "bytes_per_region": args.max_bytes, "network_bytes_total": args.max_total_bytes},
              "references": {}, "regions": []}
    fetcher = Fetcher(args.work_dir, headers, args.max_total_bytes)
    code = 0
    try:
        raw = args.manifest.read_bytes()
        report["manifest_sha256"] = hashlib.sha256(raw).hexdigest()
        report["regions"] = parse_manifest(raw.decode("utf-8-sig"), args.base_url)
        for label, path in references.items():
            try:
                report["references"][label] = {"status": "ready", "path": str(path.resolve()),
                                               "fingerprint": fingerprint(path, args.ffprobe)}
            except Exception as exc:
                report["references"][label] = {"status": "unknown", "reason": str(exc)}
        for region in report["regions"]:
            region.update(status="unknown", action="preserve", matched_references=[])
            reason = budget_reason(region, args.max_seconds, args.max_segments)
            if reason or not args.fetch:
                region["reason"] = reason or "not_fetched"
                continue
            try:
                candidate_bytes = 0
                for segment in region["segments"]:
                    downloaded = fetcher.fetch(segment["url"], args.max_bytes - candidate_bytes)
                    segment["download"] = downloaded
                    candidate_bytes += downloaded["bytes"]
                region["downloaded_bytes"] = candidate_bytes
                output = remux(region, args.work_dir, args.ffmpeg, args.ffprobe)
                region["sample_mp4"] = str(output.resolve())
                fp = fingerprint(output, args.ffprobe)
                region["fingerprint"] = fp
                region["matched_references"] = [label for label, ref in report["references"].items()
                                                 if ref["status"] == "ready" and same_content(fp, ref["fingerprint"])]
                region["status"] = "reference_content_match" if region["matched_references"] else "unknown"
                region["reason"] = "complete_video_and_audio_match" if region["matched_references"] else "no_complete_reference_match"
            except Exception as exc:
                region["reason"] = "%s:%s" % (type(exc).__name__, str(exc))
    except Exception as exc:
        report["error"] = "%s:%s" % (type(exc).__name__, str(exc))
        code = 1
    report["cost"] = {"network_bytes": fetcher.network_bytes, "cache_reuse_count": fetcher.cache_reuse_count,
                      "cache_reuse_bytes": fetcher.cache_reuse_bytes, "elapsed_seconds": time.monotonic() - started}
    destination = args.work_dir / "report.json"
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(destination)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
