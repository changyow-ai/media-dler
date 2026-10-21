#!/usr/bin/env python3
"""Local, read-only multi-signal shadow experiment; every action is preserve.

Schema 1: {"schema":1,"cases":[{"id":"...","candidate":{"path":"clip.mp4",
"start_seconds":0,"duration_seconds":10},"before":{...},"after":{...},
"boundaries":{"start":"discontinuity","end":"discontinuity"},"annotations":{}}]}.
Paths are relative to the cases JSON. Annotations and IDs are copied, never scored.
Thresholds are frozen experimental-v1 heuristics, not learned accuracy claims.
"""
import argparse
import array
import csv
import io
import json
import math
import re
import shutil
import statistics
import struct
import subprocess
import sys
import tempfile
import unicodedata
import zlib
from pathlib import Path

POLICY = "experimental-v1"
POLICY_SETTINGS = {
    "version": POLICY,
    "candidate_max_seconds": 60, "context_seconds": 2,
    "rgb_width": 64, "rgb_height": 36, "visual_max_samples": 30,
    "video_frame_mode": "native_fps_mode_passthrough", "rgb_pixel_format": "rgb24",
    "decode_seek_lookback_seconds": 10, "decode_input_tail_seconds": 2,
    "decode_window_policy": "bounded_relative_input_seek_copyts_absolute_presentation_trim_showinfo_then_reset_pts_no_output_time_trim",
    "decode_presentation_clock": "preserve_original_pts_trim_at_format_start_time_plus_requested_start",
    "decode_trim_interval": "half_open_absolute_start_end_not_duration_from_first_kept_frame",
    "color_bins_per_channel": 8, "black_max_channel": 16,
    "near_static_mae": 1 / 255, "native_window_seconds": 1, "native_percentile": .9,
    "bridge_join_factor": 4, "bridge_join_floor": 2 / 255,
    "bridge_seam_factor": 4, "bridge_seam_join_floor": 1 / 255,
    "bridge_seam_floor": 4 / 255, "bridge_min_native_p90": 1 / 255,
    "audio_rate": 8000, "audio_channels": 1, "audio_sample_format": "s16le",
    "audio_numerical_peak_floor": 1,
    "style_min_gain_db": 6, "style_min_flat_color": .35,
    "ocr_candidate_fractions": [.1, .5, .9], "ocr_context_fractions": [.5],
    "ocr_sample_interval": "requested_av_window_intersection_with_video_presentation_interval",
    "ocr_fraction_target_selection": "nearest_decoded_native_frame_timestamp_distinct_actual_times",
    "ocr_png_extraction": "same_bounded_decode_window_and_trim_select_exact_native_frame_index",
    "missing_video_duration_fallback": "conservatively_require_video_through_requested_window_end",
    "missing_video_start_fallback": "conservatively_require_video_across_requested_window",
    "ocr_min_confidence": .8, "ocr_min_distinct_times": 2,
    "tesseract_language": "eng", "tesseract_psm": 11,
    "ocr_max_width": 1920, "ocr_max_png_bytes": 32 * 1024 * 1024,
    "max_native_frames": 14401, "subprocess_timeout_seconds": 90,
    "ocr_timeout_seconds": 30, "requested_duration_tolerance_seconds": .1,
    "coverage_tolerance_seconds": .15,
}
WIDTH, HEIGHT = POLICY_SETTINGS["rgb_width"], POLICY_SETTINGS["rgb_height"]
FRAME_BYTES = WIDTH * HEIGHT * 3
MAX_FRAMES = POLICY_SETTINGS["max_native_frames"]
TIMEOUT = POLICY_SETTINGS["subprocess_timeout_seconds"]


class Unknown(ValueError):
    pass


def run(command, data=None, timeout=TIMEOUT):
    try:
        result = subprocess.run(command, input=data, capture_output=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as exc:
        raise Unknown("subprocess_failed:" + type(exc).__name__) from exc
    if result.returncode:
        raise Unknown("subprocess_exit_%s:%s" % (result.returncode, result.stderr.decode("utf-8", "replace")[-1200:]))
    return result


def number(value, name, positive=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise Unknown("invalid_" + name)
    if value < 0 or positive and value <= 0:
        raise Unknown("invalid_" + name)
    return float(value)


def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    return values[min(len(values) - 1, math.ceil(fraction * len(values)) - 1)]


def mae(left, right):
    return sum(abs(a - b) for a, b in zip(left, right)) / (len(left) * 255)


def indices(count, maximum=POLICY_SETTINGS["visual_max_samples"]):
    return sorted({round(i * (count - 1) / max(1, min(count, maximum) - 1))
                   for i in range(min(count, maximum))})


def validate_summary_mode(summary_mode):
    if summary_mode not in ("full", "weighted_decision"):
        raise Unknown("invalid_summary_mode")


def visual_summary(frames, times, role, summary_mode="full"):
    validate_summary_mode(summary_mode)
    if summary_mode == "weighted_decision":
        # Weighted scoring needs native movement only at the two context edges.
        # Keep the original pair predicate, including its inclusive boundary;
        # do not calculate MAEs for pairs outside that window.
        result = {"frame_count": len(frames), "summary_profile": summary_mode,
                  "omitted_diagnostics": ["sample_count", "quantization_bins_per_channel",
                      "top_color_bin_median", "black_pixel_fraction_median",
                      "adjacent_mae_median", "near_static"]}
        if role not in ("before", "after"):
            result["omitted_diagnostics"].extend([
                "native_edge_window_seconds", "native_adjacent_count", "native_adjacent_mae_p90"])
            return result
        edge = times[-1] if role == "before" else times[0]
        native = [mae(frames[i], frames[i + 1]) for i in range(len(frames) - 1)
                  if (times[i] >= edge - POLICY_SETTINGS["native_window_seconds"] if role == "before"
                      else times[i + 1] <= edge + POLICY_SETTINGS["native_window_seconds"])]
        result.update(native_edge_window_seconds=POLICY_SETTINGS["native_window_seconds"],
                      native_adjacent_count=len(native),
                      native_adjacent_mae_p90=percentile(native, POLICY_SETTINGS["native_percentile"]))
        return result
    sampled = [frames[i] for i in indices(len(frames))]
    top, black = [], []
    for frame in sampled:
        bins = {}
        dark = 0
        for i in range(0, len(frame), 3):
            rgb = frame[i:i + 3]
            key = tuple(c // (256 // POLICY_SETTINGS["color_bins_per_channel"]) for c in rgb)
            bins[key] = bins.get(key, 0) + 1
            dark += max(rgb) <= POLICY_SETTINGS["black_max_channel"]
        top.append(max(bins.values()) / (WIDTH * HEIGHT))
        black.append(dark / (WIDTH * HEIGHT))
    adjacent = [mae(a, b) for a, b in zip(frames, frames[1:])]
    edge = times[-1] if role == "before" else times[0]
    native = [value for i, value in enumerate(adjacent)
              if (times[i] >= edge - POLICY_SETTINGS["native_window_seconds"] if role == "before" else times[i + 1] <= edge + POLICY_SETTINGS["native_window_seconds"])]
    return {"frame_count": len(frames), "sample_count": len(sampled),
            "quantization_bins_per_channel": POLICY_SETTINGS["color_bins_per_channel"], "top_color_bin_median": statistics.median(top),
            "black_pixel_fraction_median": statistics.median(black),
            "adjacent_mae_median": statistics.median(adjacent) if adjacent else None,
            "near_static": bool(adjacent) and statistics.median(adjacent) <= POLICY_SETTINGS["near_static_mae"],
            "native_edge_window_seconds": POLICY_SETTINGS["native_window_seconds"], "native_adjacent_count": len(native),
            "native_adjacent_mae_p90": percentile(native, POLICY_SETTINGS["native_percentile"])}


def audio_summary(raw):
    samples = array.array("h")
    samples.frombytes(raw)
    if sys.byteorder != "little":
        samples.byteswap()
    if not samples:
        return {"status": "absent", "reason": "no_decoded_audio_samples"}
    peak = max(abs(x) for x in samples)
    rms = math.sqrt(sum(x * x for x in samples) / len(samples))
    return {"status": "ok", "sample_rate": POLICY_SETTINGS["audio_rate"], "sample_count": len(samples),
            "rms_pcm16": rms, "peak_pcm16": peak, "numerical_floor": peak <= POLICY_SETTINGS["audio_numerical_peak_floor"]}


def decode_window(ffmpeg, path, start, duration, origin):
    """Keep keyframe preroll and B-frame drain inside a bounded input window.

    Disable filter reinitialization so native frame indices survive resolution
    changes, including PNG extraction (select must not restart from zero).
    copyts retains the original presentation clock even when audio starts first.
    Trim in that clock before showinfo/select; reset timestamps only afterward.
    Output -t can discard frames after showinfo has already logged them.
    """
    seek = max(0, start - POLICY_SETTINGS["decode_seek_lookback_seconds"])
    trim_start = origin + start
    input_duration = start - seek + duration + POLICY_SETTINGS["decode_input_tail_seconds"]
    command = [ffmpeg, "-copyts", "-nostdin", "-xerror", "-protocol_whitelist", "file,pipe",
               "-ss", str(seek), "-t", str(input_duration), "-reinit_filter", "0", "-i", str(path)]
    return command, trim_start


def load_slice(spec, role, base, ffmpeg, ffprobe, summary_mode="full"):
    validate_summary_mode(summary_mode)
    if not isinstance(spec, dict):
        raise Unknown("missing_" + role)
    if spec.get("partial") or spec.get("truncated") or spec.get("complete") is False:
        raise Unknown("explicit_partial_input")
    value = spec.get("path")
    if not isinstance(value, str) or "://" in value or not value:
        raise Unknown("requires_local_file_path")
    path = (base / value).resolve()
    if not path.is_file():
        raise Unknown("missing_local_file")
    metadata = json.loads(run([ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe",
                               "-show_streams", "-show_format", "-of", "json", str(path)]).stdout)
    videos = [s for s in metadata.get("streams", []) if s.get("codec_type") == "video"]
    if not videos:
        raise Unknown("no_video_stream")

    def optional_time(value):
        if value is None or value == "N/A":
            return None
        value = float(value)
        if not math.isfinite(value):
            raise Unknown("invalid_media_timestamp")
        return value

    container = metadata.get("format", {})
    format_start = optional_time(container.get("start_time"))
    if format_start is None:
        raise Unknown("unknown_format_start_time_for_presentation_clock")
    video_start = optional_time(videos[0].get("start_time"))
    video_duration = optional_time(videos[0].get("duration"))
    available = optional_time(container.get("duration"))
    relative_video_start = video_start - format_start if video_start is not None and format_start is not None else None
    if available is None and video_duration is not None:
        available = video_duration + (relative_video_start or 0)
    if available is None or available <= 0 or video_duration is not None and video_duration <= 0:
        raise Unknown("unknown_media_duration")
    start = number(spec.get("start_seconds", 0), "start_seconds")
    duration = number(spec.get("duration_seconds", available - start), "duration_seconds", True)
    if start + duration > available + POLICY_SETTINGS["requested_duration_tolerance_seconds"]:
        raise Unknown("requested_slice_exceeds_media_duration")
    if role == "candidate" and duration > POLICY_SETTINGS["candidate_max_seconds"]:
        raise Unknown("candidate_duration_exceeds_60_seconds")
    if role == "before":
        start += max(0, duration - POLICY_SETTINGS["context_seconds"])
    if role != "candidate":
        duration = min(duration, POLICY_SETTINGS["context_seconds"])
    # FFmpeg input -ss is relative to the container's start_time. Decode the full
    # requested AV window; requiring video through an AAC-only tail is incorrect.
    expected_start, expected_end = 0.0, duration
    coverage_basis = "requested_window_conservative_fallback"
    if relative_video_start is not None:
        expected_start = max(start, relative_video_start) - start
        if video_duration is not None:
            expected_end = min(start + duration, relative_video_start + video_duration) - start
            coverage_basis = "video_presentation_interval_intersection"
        else:
            coverage_basis = "video_start_to_requested_end_conservative_missing_video_duration"
    if expected_end <= expected_start:
        raise Unknown("requested_window_has_no_video")
    common, trim_start = decode_window(ffmpeg, path, start, duration, format_start)
    video_trim = "trim=start=%s:end=%s" % (trim_start, trim_start + duration)
    decoded = run(common[:1] + ["-v", "info"] + common[1:] + [
        "-map", "0:v:0", "-an", "-vf", video_trim + ",scale=%s:%s,showinfo,setpts=PTS-STARTPTS" % (WIDTH, HEIGHT),
        # Legacy alias supported by the Android FFmpeg build; same passthrough.
        "-vsync", "0",
        "-pix_fmt", POLICY_SETTINGS["rgb_pixel_format"], "-frames:v", str(MAX_FRAMES), "-f", "rawvideo", "pipe:1"])
    if not decoded.stdout or len(decoded.stdout) % FRAME_BYTES:
        raise Unknown("incomplete_video_decode")
    frames = [decoded.stdout[i:i + FRAME_BYTES] for i in range(0, len(decoded.stdout), FRAME_BYTES)]
    times = [float(x) - trim_start for x in re.findall(rb"\bn:\s*\d+\s+pts:\s*\S+\s+pts_time:([-+\d.eE]+)", decoded.stderr)]
    if len(frames) >= MAX_FRAMES or len(times) != len(frames) or len(frames) < 2:
        raise Unknown("native_frame_budget_or_timestamps_or_insufficient_frames")
    gaps = [b - a for a, b in zip(times, times[1:]) if b > a]
    if not gaps:
        raise Unknown("partial_decoded_video_coverage")
    frame_duration = statistics.median(gaps)
    tolerance = max(POLICY_SETTINGS["coverage_tolerance_seconds"], frame_duration)
    decoded_end = times[-1] + frame_duration
    if (times[0] > expected_start + tolerance or decoded_end < expected_end - tolerance
            or decoded_end - times[0] < expected_end - expected_start - tolerance):
        raise Unknown("partial_decoded_video_coverage")
    coverage = {"basis": coverage_basis, "video_start_relative_to_format_seconds": relative_video_start,
                "video_duration_seconds": video_duration, "expected_start_in_slice_seconds": expected_start,
                "expected_end_in_slice_seconds": expected_end, "decoded_first_in_slice_seconds": times[0],
                "decoded_last_in_slice_seconds": times[-1], "median_native_frame_duration_seconds": frame_duration,
                "tolerance_seconds": tolerance}
    if any(s.get("codec_type") == "audio" for s in metadata.get("streams", [])):
        audio = audio_summary(run(common[:1] + ["-v", "error"] + common[1:] + [
            "-map", "0:a:0", "-vn", "-af", "atrim=start=%s:end=%s,asetpts=PTS-STARTPTS" % (trim_start, trim_start + duration), "-ac", str(POLICY_SETTINGS["audio_channels"]), "-ar", str(POLICY_SETTINGS["audio_rate"]), "-f", POLICY_SETTINGS["audio_sample_format"], "pipe:1"]).stdout)
    else:
        audio = {"status": "absent", "reason": "no_audio_stream"}
    public = {"status": "ok", "path": str(path), "start_seconds": start, "duration_seconds": duration,
              "metadata": metadata, "video_coverage": coverage,
              "decode_window": {"input_seek_relative_seconds": max(0, start - POLICY_SETTINGS["decode_seek_lookback_seconds"]),
                                "trim_start_presentation_seconds": trim_start, "format_start_seconds": format_start,
                                "input_duration_seconds": min(start, POLICY_SETTINGS["decode_seek_lookback_seconds"]) + duration + POLICY_SETTINGS["decode_input_tail_seconds"]},
              "visual": visual_summary(frames, times, role, summary_mode=summary_mode), "audio": audio}
    return {"public": public, "frames": frames, "times": times, "path": path,
            "start": start, "duration": duration, "origin": format_start, "video_window": [expected_start, expected_end]}


def normalize(text):
    text = unicodedata.normalize("NFKC", text).casefold()
    return re.sub(r"\s*\.\s*", ".", text)


DOMAIN = re.compile(r"(?<![\w.-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:[a-z]{2,24})(?![\w.-])")
PROMO = re.compile(r"\b(?:register|registration|deposit|gambling|casino|betting|bet|bonus|rebate|signup)\b|"
                   r"棋牌|博彩|註冊|注册|充值|投注|返利|賭場|赌场")
PROGRAMME = re.compile(r"\b(?:elenco|cast|tradução|traducao|starring|screenplay|directed\s+by)\b|"
                       r"配音|演員|演员|字幕製作|字幕制作")


def matched_observations(sample):
    result = []
    for observation in sample.get("observations", []):
        if observation["confidence"] < POLICY_SETTINGS["ocr_min_confidence"]:
            continue
        text = normalize(observation["text"])
        domains = sorted(set(DOMAIN.findall(text)))
        promo = sorted(set(PROMO.findall(text)))
        programme = sorted(set(PROGRAMME.findall(text)))
        if domains or promo or programme:
            box = observation.get("box")
            area = box[2] * box[3] if box else None
            result.append({"text": observation["text"], "normalized": text,
                           "confidence": observation["confidence"], "box": box,
                           "box_area": area, "domains": domains,
                           "promotional_words": promo, "programme_words": programme})
    return result


def validate_ocr(data):
    if not isinstance(data, dict) or data.get("error"):
        raise Unknown("ocr_provider_error:" + str(data.get("error") if isinstance(data, dict) else "invalid_json"))
    if not isinstance(data.get("engine"), str) or not isinstance(data.get("observations"), list):
        raise Unknown("invalid_ocr_schema")
    for item in data["observations"]:
        if not isinstance(item, dict) or not isinstance(item.get("text"), str):
            raise Unknown("invalid_ocr_observation")
        confidence = number(item.get("confidence"), "ocr_confidence")
        if confidence > 1:
            raise Unknown("invalid_ocr_confidence")
        box = item.get("box")
        if not isinstance(box, list) or len(box) != 4:
            raise Unknown("invalid_ocr_box")
        for value in box:
            number(value, "ocr_box")
    return data


def ocr_png(png, command):
    if command:
        return validate_ocr(json.loads(run([command], png, timeout=POLICY_SETTINGS["ocr_timeout_seconds"]).stdout))
    tesseract = shutil.which("tesseract")
    if not tesseract:
        raise Unknown("ocr_unavailable")
    raw = run([tesseract, "stdin", "stdout", "-l", POLICY_SETTINGS["tesseract_language"],
               "--psm", str(POLICY_SETTINGS["tesseract_psm"]), "tsv"], png, timeout=POLICY_SETTINGS["ocr_timeout_seconds"])
    width, height = struct.unpack(">II", png[16:24])
    observations = []
    # Group words from one TSV line so spaced domains and credit phrases remain matchable.
    lines = {}
    for item in csv.DictReader(io.StringIO(raw.stdout.decode("utf-8", "replace")), delimiter="\t"):
        if item.get("text", "").strip() and float(item["conf"]) >= 0:
            key = tuple(item[x] for x in ("page_num", "block_num", "par_num", "line_num"))
            lines.setdefault(key, []).append(item)
    for words in lines.values():
        left = min(int(w["left"]) for w in words)
        top = min(int(w["top"]) for w in words)
        right = max(int(w["left"]) + int(w["width"]) for w in words)
        bottom = max(int(w["top"]) + int(w["height"]) for w in words)
        observations.append({"text": " ".join(w["text"] for w in words),
                             "confidence": min(float(w["conf"]) for w in words) / 100,
                             "box": [left / width, top / height, (right - left) / width, (bottom - top) / height]})
    return validate_ocr({"engine": "tesseract-eng-psm11", "observations": observations})


def ocr_width(max_width):
    value = POLICY_SETTINGS["ocr_max_width"] if max_width is None else max_width
    if type(value) is not int or not 1 <= value <= POLICY_SETTINGS["ocr_max_width"]:
        raise Unknown("invalid_ocr_max_width")
    return value


def extract_png(media, index, ffmpeg, max_width=None):
    """Original one-frame extraction, also used after any batch failure."""
    common, trim_start = decode_window(ffmpeg, media["path"], media["start"], media["duration"], media["origin"])
    png = run(common[:1] + ["-v", "error"] + common[1:] + [
        "-map", "0:v:0", "-an", "-frames:v", "1", "-vsync", "0",
        "-vf", "trim=start=%s:end=%s,select=eq(n\\,%s),scale=min(%s\\,iw):-2,setpts=PTS-STARTPTS" %
        (trim_start, trim_start + media["duration"], index, ocr_width(max_width)),
        "-f", "image2pipe", "-vcodec", "png", "pipe:1"]).stdout
    if not png.startswith(b"\x89PNG\r\n\x1a\n") or len(png) > POLICY_SETTINGS["ocr_max_png_bytes"]:
        raise Unknown("invalid_or_oversize_ocr_png")
    return png


def validate_batch_png(png):
    """Require a complete standalone PNG before handing a batch frame to OCR."""
    if (not png.startswith(b"\x89PNG\r\n\x1a\n") or len(png) > POLICY_SETTINGS["ocr_max_png_bytes"]
            or len(png) < 33 or png[8:16] != b"\x00\x00\x00\rIHDR"
            or not all(struct.unpack(">II", png[16:24]))):
        raise Unknown("invalid_or_oversize_batch_ocr_png")
    offset, has_image = 8, False
    while offset + 12 <= len(png):
        length = struct.unpack(">I", png[offset:offset + 4])[0]
        end = offset + 12 + length
        if end > len(png) or zlib.crc32(png[offset + 4:end - 4]) != struct.unpack(">I", png[end - 4:end])[0]:
            raise Unknown("malformed_batch_ocr_png")
        if png[offset + 4:offset + 8] == b"IDAT" and length:
            has_image = True
        if png[offset + 4:offset + 8] == b"IEND":
            if length or end != len(png) or not has_image:
                raise Unknown("malformed_batch_ocr_png")
            return png
        offset = end
    raise Unknown("incomplete_batch_ocr_png")


def extract_png_batch(media, indices, ffmpeg, max_width=None):
    """One strict decode for unique target indices; cap width without upscaling."""
    indices = sorted(set(indices))
    if not indices:
        return {}
    common, trim_start = decode_window(ffmpeg, media["path"], media["start"], media["duration"], media["origin"])
    select = "+".join("eq(n\\,%s)" % index for index in indices)
    with tempfile.TemporaryDirectory(prefix="hls-ocr-batch-") as temporary:
        root = Path(temporary)
        run(common[:1] + ["-v", "error"] + common[1:] + [
            "-map", "0:v:0", "-an", "-frames:v", str(len(indices)), "-vsync", "0",
            "-vf", "trim=start=%s:end=%s,select=%s,scale=min(%s\\,iw):-2,setpts=PTS-STARTPTS" %
            (trim_start, trim_start + media["duration"], select, ocr_width(max_width)),
            "-f", "image2", "-vcodec", "png", str(root / "frame-%06d.png")])
        expected = [root / ("frame-%06d.png" % (i + 1)) for i in range(len(indices))]
        if set(root.iterdir()) != set(expected):
            raise Unknown("missing_or_extra_batch_ocr_png")
        images = {}
        for index, path in zip(indices, expected):
            if path.is_symlink() or path.stat().st_size > POLICY_SETTINGS["ocr_max_png_bytes"]:
                raise Unknown("invalid_or_oversize_batch_ocr_png")
            images[index] = validate_batch_png(path.read_bytes())
        return images


def extract_ocr(media, role, ffmpeg, command, batch=False, fractions=None, max_width=None):
    if media is None:
        return {"status": "absent", "reason": "missing_or_invalid_" + role, "samples": []}
    if fractions is None:
        fractions = POLICY_SETTINGS["ocr_candidate_fractions" if role == "candidate" else "ocr_context_fractions"]
    if (not isinstance(fractions, (list, tuple)) or not fractions
            or any(isinstance(f, bool) or not isinstance(f, (int, float))
                   or not math.isfinite(f) or not 0 <= f <= 1 for f in fractions)):
        raise Unknown("invalid_ocr_sample_fractions")
    png_options = {} if max_width is None else {"max_width": ocr_width(max_width)}
    samples = []
    for fraction in fractions:
        window_start, window_end = media.get("video_window", [0, media["duration"]])
        target = window_start + (window_end - window_start) * fraction
        index = min(range(len(media["times"])), key=lambda i: abs(media["times"][i] - target))
        samples.append({"fraction": fraction, "requested_time_seconds": media["start"] + target,
                        "native_frame_index": index, "time_seconds": media["start"] + media["times"][index]})
    images, diagnostic = None, None
    if batch:
        diagnostic = {"status": "ok", "unique_frame_count": len({s["native_frame_index"] for s in samples})}
        try:
            images = extract_png_batch(media, [s["native_frame_index"] for s in samples], ffmpeg, **png_options)
        except (Unknown, OSError, ValueError, KeyError, TypeError, struct.error) as exc:
            diagnostic.update(status="fallback", reason=str(exc))
    for sample in samples:
        try:
            index = sample["native_frame_index"]
            png = images[index] if images is not None else extract_png(media, index, ffmpeg, **png_options)
            sample.update(ocr_png(png, command))
            sample["status"] = "ok"
            sample["matches"] = matched_observations(sample)
        except (Unknown, ValueError, KeyError, TypeError, struct.error) as exc:
            sample.update(status="unknown", reason=str(exc), observations=[], matches=[])
    result = {"status": "ok" if all(s["status"] == "ok" for s in samples) else "unknown", "samples": samples}
    if diagnostic is not None:
        result["batch_extraction"] = diagnostic
    return result


def promotional_evidence(ocr):
    candidate = ocr["candidate"]
    occurrences, words, contradictions = {}, set(), set()
    for sample in candidate["samples"]:
        domains = set()
        for match in sample.get("matches", []):
            domains.update(match["domains"])
            words.update(match["promotional_words"])
            contradictions.update(match["programme_words"])
        for domain in domains:
            occurrences.setdefault(domain, set()).add(sample["time_seconds"])
    outside = set()
    for role in ("before", "after"):
        for sample in ocr[role]["samples"]:
            for match in sample.get("matches", []):
                outside.update(match["domains"])
    persistent = sorted(d for d, times in occurrences.items() if len(times) >= POLICY_SETTINGS["ocr_min_distinct_times"] and d not in outside)
    reliable = candidate["status"] == "ok" and all(ocr[r]["status"] != "unknown" for r in ("before", "after"))
    return {"status": "strong" if reliable and persistent and words else "unknown",
            "domain_distinct_time_counts": {d: len(t) for d, t in sorted(occurrences.items())},
            "persistent_new_domains": persistent, "outside_domains": sorted(outside),
            "outside_ocr_status": {r: ocr[r]["status"] for r in ("before", "after")},
            "promotional_words": sorted(words), "programme_contradictions": sorted(contradictions),
            "reason": "persistent_domain_and_promotion" if reliable and persistent and words else "insufficient_or_conflicting_ocr_evidence"}


def bridge_evidence(media, boundaries):
    if boundaries != {"start": "discontinuity", "end": "discontinuity"}:
        return {"status": "unknown", "reason": "requires_two_discontinuity_boundaries"}
    if not all(media.get(r) for r in ("before", "candidate", "after")):
        return {"status": "unknown", "reason": "requires_both_valid_contexts_and_candidate"}
    before, candidate, after = (media[r] for r in ("before", "candidate", "after"))
    start = mae(before["frames"][-1], candidate["frames"][0])
    end = mae(candidate["frames"][-1], after["frames"][0])
    joined = mae(before["frames"][-1], after["frames"][0])
    sides = [m["public"]["visual"]["native_adjacent_mae_p90"] for m in (before, after)]
    values = {"start_seam_mae": start, "end_seam_mae": end, "joined_edge_mae": joined,
              "side_native_adjacent_mae_p90": sides}
    if any(p is None for p in sides) or max(sides) < POLICY_SETTINGS["bridge_min_native_p90"]:
        return dict(values, status="unknown", reason="static_or_low_information_context")
    joined_limit = max(POLICY_SETTINGS["bridge_join_factor"] * max(sides), POLICY_SETTINGS["bridge_join_floor"])
    seam_limit = max(POLICY_SETTINGS["bridge_seam_factor"] * max(joined, POLICY_SETTINGS["bridge_seam_join_floor"]), POLICY_SETTINGS["bridge_seam_floor"])
    strong = joined <= joined_limit and min(start, end) >= seam_limit
    return dict(values, joined_limit=joined_limit, minimum_original_seam_limit=seam_limit,
                status="strong" if strong else "unknown", reason="temporal_bridge_heuristic" if strong else "bridge_thresholds_not_met")


def style_evidence(media):
    gains = {}
    candidate = media.get("candidate")
    for role in ("before", "after"):
        pair = [candidate, media.get(role)]
        audio = [m["public"]["audio"] if m else {} for m in pair]
        if any(a.get("status") != "ok" or a.get("numerical_floor", True) for a in audio):
            gains[role] = {"status": "unknown", "reason": "missing_audio_or_numerical_floor"}
        else:
            gain = 20 * math.log10(audio[0]["rms_pcm16"] / audio[1]["rms_pcm16"])
            gains[role] = {"status": "ok", "rms_gain_db": gain}
    flat = candidate["public"]["visual"]["top_color_bin_median"] if candidate else None
    strong = flat is not None and flat >= POLICY_SETTINGS["style_min_flat_color"] and all(g["status"] == "ok" and g["rms_gain_db"] >= POLICY_SETTINGS["style_min_gain_db"] for g in gains.values())
    return {"status": "strong" if strong else "unknown", "votes_in_decision": False,
            "relative_audio": gains, "top_color_bin_median": flat}


def decide(bridge, promotion, valid):
    if promotion["programme_contradictions"]:
        return "retain_programme"
    if valid and bridge["status"] == promotion["status"] == "strong":
        return "experimental_ad_candidate"
    return "unknown"


def process_case(case, base, ffmpeg, ffprobe, command):
    media, inputs, errors = {}, {}, []
    for role in ("candidate", "before", "after"):
        try:
            media[role] = load_slice(case.get(role), role, base, ffmpeg, ffprobe)
            inputs[role] = media[role]["public"]
        except (Unknown, ValueError, KeyError, TypeError) as exc:
            media[role] = None
            inputs[role] = {"status": "unknown" if case.get(role) is not None else "absent", "reason": str(exc)}
            errors.append(role + ":" + str(exc))
    ocr = {r: extract_ocr(media[r], r, ffmpeg, command) for r in media}
    promotion = promotional_evidence(ocr)
    bridge = bridge_evidence(media, case.get("boundaries", {}))
    partial = case.get("partial") or case.get("truncated") or case.get("complete") is False
    valid = media["candidate"] is not None and not partial
    if partial:
        errors.append("case:explicit_partial_input")
    return {"id": case.get("id"), "annotations": case.get("annotations", {}),
            "boundaries": case.get("boundaries", {}), "inputs": inputs, "errors": errors,
            "ocr": ocr, "content_only_P": promotion, "bridge_only_S": bridge,
            "style_W": style_evidence(media), "combined": decide(bridge, promotion, valid),
            "candidate_gate": "valid" if valid else "unknown", "action": "preserve"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", required=True, type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--ocr-command", help="Executable reading PNG bytes on stdin and emitting OCR JSON")
    args = parser.parse_args(argv)
    source = args.cases.resolve()
    data = json.loads(source.read_text())
    if not isinstance(data, dict) or data.get("schema", data.get("schema_version")) != 1 or not isinstance(data.get("cases"), list):
        parser.error("requires schema 1 and cases array")
    if any(not isinstance(c, dict) for c in data["cases"]):
        parser.error("each case must be an object")
    # mkdir is exclusive, including empty directories; never touch existing outputs or sources.
    args.work_dir.mkdir(parents=True, exist_ok=False)
    report = {"schema": 1, "policy": POLICY, "policy_settings": POLICY_SETTINGS,
              "ocr_match_patterns": {"domain": DOMAIN.pattern, "promotion": PROMO.pattern, "programme": PROGRAMME.pattern},
              "action": "preserve",
              "limitations": ["Scene/temporal plausibility is not semantic continuity proof.",
                              "Fixed experimental thresholds; no calibrated accuracy claim.",
                              "OCR absence is not proof of absence; no automatic removal."],
              "cases": [process_case(c, source.parent, args.ffmpeg, args.ffprobe, args.ocr_command) for c in data["cases"]]}
    with (args.work_dir / "report.json").open("x") as output:
        json.dump(report, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write("\n")
    with (args.work_dir / "review.csv").open("x", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(["id", "candidate_gate", "S", "P", "W", "combined", "action", "errors"])
        for case in report["cases"]:
            writer.writerow([case["id"], case["candidate_gate"], case["bridge_only_S"]["status"],
                             case["content_only_P"]["status"], case["style_W"]["status"],
                             case["combined"], case["action"], "; ".join(case["errors"])])
    print(str((args.work_dir / "report.json").resolve()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
