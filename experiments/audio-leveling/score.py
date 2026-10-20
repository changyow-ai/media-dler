#!/usr/bin/env python3
"""Evaluate measured CSVs, never process media or pass labels to processing.

Usage: python3 experiments/audio-leveling/score.py build/audio-leveling [--suffix=-v2]
An optional config follows the root. Each clip may override its directory with
"path" (relative to root, or absolute); --suffix applies only to default names.
"""

import argparse
import csv
import json
import math
from pathlib import Path
import statistics


def read_frames(path):
    frames = {}
    with path.open(newline="") as source:
        for row in csv.DictReader(source):
            t, momentary, shortterm = (float(row[k]) for k in
                                      ("t", "momentary_lufs", "shortterm_lufs"))
            if not all(map(math.isfinite, (t, momentary, shortterm))) or round(t, 1) in frames:
                raise ValueError(f"Nonfinite or duplicate frame in {path}: {row}")
            # FFmpeg reports small timestamp offsets after filter/re-encode;
            # match the nominal 100 ms measurement tick, not binary equality.
            frames[round(t, 1)] = (momentary, shortterm, t)
    return frames


def percentile(values, fraction):
    """Linearly interpolate between neighboring sorted values."""
    values = sorted(values)
    position = (len(values) - 1) * fraction
    lower, upper = math.floor(position), math.ceil(position)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def rounded(value):
    return round(value, 3) if value is not None else None


def attenuation(original, frames, times, index):
    selected = [t for t in times if index == 1 or original[t][0] > -60]
    trace = [(t, rounded(original[t][index] - frames[t][index])) for t in selected]
    values = [value for _, value in trace]
    run = longest = 0
    previous = None
    for t, value in trace:
        run = run + 1 if value > 0.5 and previous is not None and t - previous < 0.100001 else int(value > 0.5)
        longest = max(longest, run)
        previous = t
    return {
        "eligible_frames": len(selected), "excluded_momentary_gate_frames": len(times) - len(selected),
        "eligible_seconds": rounded(len(selected) * 0.1),
        "p95_lu": rounded(percentile(values, 0.95)) if values else None,
        "max_lu": rounded(max(values)) if values else None,
        "affected": {str(threshold): {
            "seconds": rounded(sum(value > threshold for value in values) * 0.1),
            "fraction": rounded(sum(value > threshold for value in values) / len(values)) if values else None
        } for threshold in (0.5, 1, 2)},
        "longest_contiguous_over_0.5_lu_seconds": rounded(longest * 0.1),
        "trace_time_seconds_attenuation_lu": [[original[t][2], rounded(value)] for t, value in trace]
    }


def impact(original, frames, times):
    return {name: attenuation(original, frames, times, index)
            for name, index in (("momentary", 0), ("shortterm", 1))}


def evaluate(directory, clip):
    metrics = json.loads((directory / "metrics.json").read_text())
    for config_key, metric_key in (("duration_seconds", "duration_seconds"),
                                   ("source_start_seconds", "start_seconds")):
        if not math.isclose(clip[config_key], metrics[metric_key], abs_tol=1e-6):
            raise ValueError(f"{directory}: {config_key} disagrees with metrics")
    variants = metrics["variants"]
    files = {p.stem: p for p in directory.glob("*.csv") if p.stem in variants}
    if set(files) != set(variants) or "original" not in files:
        raise ValueError(f"{directory}: require original and a CSV for every metrics variant")
    series = {name: read_frames(path) for name, path in files.items()}
    original = series["original"]
    duration = clip["duration_seconds"]
    regions = {"content": (0, duration)}
    boundaries = {}
    onset_times = []
    if "ad_seconds" in clip:
        start, end = clip["ad_seconds"]
        if not 0 < start < end < duration:
            raise ValueError(f"{directory}: invalid ad boundaries")
        regions = {"before": (0, start), "ad": (start, end), "after": (end, duration)}
        boundaries = {"before_ad_last3s": (max(0, start - 3), start),
                      "ad_first3s": (start, min(end, start + 3)),
                      "ad_last3s": (max(start, end - 3), end),
                      "after_ad_first3s": (end, min(duration, end + 3))}
        onset_times = [t for t, row in original.items() if start + 0.4 <= row[2] < start + 1.0]
        if not onset_times:
            raise ValueError(f"{directory}: no onset frames")
    eligible = {name: [t for t, (_, s, actual_t) in original.items()
                       if start + 3 <= actual_t < end and s > -50]
                for name, (start, end) in regions.items()}
    if any(not times for times in eligible.values()):
        raise ValueError(f"{directory}: region has no eligible original frames")
    boundary_times = {name: [t for t, (_, s, actual_t) in original.items()
                             if start <= actual_t < end and actual_t >= 3 and s > -50]
                      for name, (start, end) in boundaries.items()}
    result = {"directory": str(directory.resolve()), "regions_seconds": regions,
              "boundary_regions_seconds": boundaries,
              "eligible_nominal_frame_times_seconds": eligible,
              "eligible_original_frame_times_seconds": {
                  name: [original[t][2] for t in times] for name, times in eligible.items()},
              "onset_nominal_frame_times_seconds": onset_times, "variants": {}}
    for name in variants:
        frames = series[name]
        required = set(onset_times).union(*(set(times) for times in
                                           list(eligible.values()) + list(boundary_times.values())))
        if required - frames.keys():
            raise ValueError(f"{directory}/{name}: missing matched frame timestamps")
        row = {"regions": {}, "boundaries": {}, "true_peak_dbtp": variants[name]["summary"]["true_peak_dbtp"],
               "max_matched_timestamp_offset_seconds": rounded(max(
                   abs(frames[t][2] - original[t][2]) for t in required))}
        medians = {}
        for region, times in eligible.items():
            values = [frames[t][1] for t in times]
            medians[region] = statistics.median(values)
            row["regions"][region] = {
                "frames": len(times), "median_shortterm_lufs": rounded(medians[region]),
                "p90_shortterm_lufs": rounded(percentile(values, 0.9)),
                "attenuation": impact(original, frames, times),
                "median_matched_gain_lu": rounded(statistics.median(
                    frames[t][1] - original[t][1] for t in times))}
        row["boundaries"] = {boundary: {"attenuation": impact(original, frames, times)}
                             for boundary, times in boundary_times.items()}
        if onset_times:
            row["ad_minus_before_lu"] = rounded(medians["ad"] - medians["before"])
            row["ad_minus_after_lu"] = rounded(medians["ad"] - medians["after"])
            row["ad_first_second_momentary_max_lufs"] = max(frames[t][0] for t in onset_times)
        result["variants"][name] = row
    return result


def table(clips):
    def number(value):
        return f"{value:.1f}" if value is not None else "-"

    def region(row, name):
        values = row["regions"].get(name)
        if values is None:
            return "-"
        return "/".join(number(values[k]) for k in
                        ("median_shortterm_lufs", "p90_shortterm_lufs", "median_matched_gain_lu"))

    rows = [["clip", "profile", "before/content", "ad", "after", "gap B/A", "M1 max", "TP", "M>0.5s B/C,A,F"]]
    for clip, result in clips.items():
        for profile, row in result["variants"].items():
            gaps = "/".join(number(row.get(k)) for k in ("ad_minus_before_lu", "ad_minus_after_lu"))
            affected = "/".join(number(row["regions"][region]["attenuation"]["momentary"]["affected"]["0.5"]["seconds"])
                                if region in row["regions"] else "-" for region in
                                ("before" if "before" in row["regions"] else "content", "ad", "after"))
            rows.append([clip, profile, region(row, "before" if "before" in row["regions"] else "content"),
                         region(row, "ad"), region(row, "after"), gaps,
                         number(row.get("ad_first_second_momentary_max_lufs")), number(row["true_peak_dbtp"]), affected])
    widths = [max(len(row[i]) for row in rows) for i in range(len(rows[0]))]
    return "\n".join("  ".join(value.ljust(width) for value, width in zip(row, widths)) for row in rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("config", nargs="?", type=Path,
                        default=Path(__file__).with_name("evaluation-windows.json"))
    parser.add_argument("--suffix", default="", help="Append to default clip directory names, e.g. --suffix=-v2")
    parser.add_argument("--output", type=Path, help="Default: ROOT/evaluation.json")
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    result = {
        "root": str(args.root.resolve()), "suffix": args.suffix,
        "evaluation_config": config,
        "method": {
            "shortterm": "[region start + 3 s, region end); original shortterm > -50 LUFS only",
            "matching": "Identical original-eligible nominal 100 ms ticks, rounded from CSV timestamps; duplicate/missing ticks fail; actual timing offset reported",
            "p90": "Linear interpolation between sorted neighboring values",
            "gain": "Median per-frame variant minus original shortterm LUFS, in LU",
            "attenuation": "Original minus variant in LU; M400 ms and S3 s use the original S eligibility mask. M additionally excludes original M <= -60 LUFS, reporting excluded frames. No processed-level gate.",
            "boundaries": "Four explicit 3 s windows, original S > -50 LUFS and timestamp >= 3 s; M additionally original M > -60. No per-boundary 3 s exclusion. Trailing M/S windows can mix adjoining labels, especially S3 s at transitions.",
            "impact_time": "Approximate seconds = eligible 100 ms frames * 0.1; fractions use gated eligible frames. Thresholds >0.5/>1/>2 LU are descriptive bins, not acceptance criteria. Trace timestamps are trailing-window measurement endpoints; gated gaps break contiguous runs.",
            "gaps": "Ad median minus before/after median, in LU",
            "onset": "Max momentary LUFS at [ad start + 0.4 s, ad start + 1.0 s); no level gate",
            "true_peak": "Whole-clip value from variants[profile].summary.true_peak_dbtp",
            "limits": "Descriptive measurements only; no pass/fail thresholds or audibility conclusions"
        },
        "clips": {name: evaluate(args.root / clip.get("path", name + args.suffix), clip)
                  for name, clip in config["clips"].items()}
    }
    output = args.output or args.root / "evaluation.json"
    output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    print("Regions: median/p90 shortterm LUFS / matched median gain LU. Gap B/A: ad minus before/after LU.")
    print("M1 max: momentary LUFS within first second after +0.4 s; TP: whole-clip dBTP.")
    print("M>0.5s B/C,A,F: eligible seconds with >0.5 LU momentary attenuation in before/content, ad, after.")
    print(table(result["clips"]))
    print(f"Saved {output}\n{config.get('coverage', '')}")


if __name__ == "__main__":
    main()
