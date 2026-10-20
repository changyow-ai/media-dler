#!/usr/bin/env python3
"""Frozen paired loudness-step experiment. Input is full-recording audio CSV.

No ad labels, fingerprints, expected ad durations, or selected clip offsets are
accepted. Outputs are proposals for evaluation, not a production media filter.
"""

import argparse
import csv
import hashlib
import json
from pathlib import Path
import statistics


def median(values):
    return statistics.median(values)


def occupancy(values, threshold):
    return sum(value > threshold for value in values) / len(values)


def detect(rows):
    levels = [float(row["momentary_lufs"]) for row in rows]
    times = [float(row["t"]) - 0.2 for row in rows]
    proposals = []
    # This is the onset screen from the initial blind whole-recording pass.
    for i in range(100, len(levels) - 110, 5):
        preceding = levels[i - 100:i - 10]
        active = [value for value in preceding if value > -45]
        if len(active) < 0.6 * len(preceding):
            continue
        baseline = median(active)
        edge_rise = median(levels[i + 2:i + 12]) - median(levels[i - 10:i])
        hold_rise = median(levels[i:i + 20]) - baseline
        if edge_rise < 6 or hold_rise < 6:
            continue
        if proposals and times[i] - proposals[-1]["start_seconds"] <= 5:
            continue
        proposal = {"start_seconds": times[i], "baseline_lufs": baseline,
                    "edge_rise_lu": edge_rise, "hold_rise_lu": hold_rise}
        proposals.append(proposal)
        for j in range(i + 20, len(levels) - 30, 5):
            drop = median(levels[j - 20:j]) - median(levels[j:j + 20])
            returned = 1 - occupancy(levels[j:j + 30], baseline + 3)
            if drop < 5 or returned < 0.8:
                continue
            one_second_levels = [median(levels[k:k + 10]) for k in range(i, j - 9, 10)]
            sustained = occupancy(one_second_levels, baseline + 4)
            block_median = median(levels[i:j])
            proposal.update(end_seconds=times[j], return_drop_lu=drop,
                            return_occupancy=returned, plateau_occupancy=sustained,
                            block_median_lufs=block_median,
                            attenuation_db=min(6, max(0, block_median - (baseline + 3))))
            proposal["accepted"] = sustained >= 0.8
            proposal["reason"] = "paired_return_and_plateau" if proposal["accepted"] else "insufficient_plateau"
            break
        else:
            proposal.update(accepted=False, reason="no_qualifying_return")
    confirmed = [p for p in proposals if p["accepted"]]
    merged = []
    for proposal in confirmed:
        if merged and proposal["start_seconds"] <= merged[-1]["end_seconds"]:
            merged[-1]["end_seconds"] = max(merged[-1]["end_seconds"], proposal["end_seconds"])
            merged[-1]["proposal_starts"].append(proposal["start_seconds"])
        else:
            merged.append({"start_seconds": proposal["start_seconds"],
                           "end_seconds": proposal["end_seconds"],
                           "baseline_lufs": proposal["baseline_lufs"],
                           "proposal_starts": [proposal["start_seconds"]]})
    for block in merged:
        values = [value for t, value in zip(times, levels) if block["start_seconds"] <= t < block["end_seconds"]]
        block["block_median_lufs"] = median(values)
        block["attenuation_db"] = min(6, max(0, median(values) - (block["baseline_lufs"] + 3)))
    return proposals, merged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    raw = args.csv.read_bytes()
    with args.csv.open(newline="") as source:
        rows = list(csv.DictReader(source))
    proposals, blocks = detect(rows)
    result = {
        "experiment": "paired_step_v1_frozen", "input_csv": str(args.csv.resolve()),
        "input_sha256": hashlib.sha256(raw).hexdigest(),
        "parameters": {"entry_jump_lu": 6, "entry_hold_lu": 6, "entry_hold_seconds": 2,
                       "reference_seconds": [10, 1], "reference_active_threshold_lufs": -45,
                       "reference_active_fraction": 0.6, "return_drop_lu": 5,
                       "return_occupancy_fraction": 0.8, "return_window_seconds": 3,
                       "return_relative_ceiling_lu": 3, "plateau_margin_lu": 4,
                       "plateau_fraction": 0.8, "max_attenuation_db": 6,
                       "stride_seconds": 0.5, "proposal_dedup_seconds": 5,
                       "meter_centering_seconds": 0.2},
        "proposals": proposals, "merged_blocks": blocks,
        "summary": {"onset_proposals": len(proposals),
                    "accepted_proposals": sum(p["accepted"] for p in proposals),
                    "merged_blocks": len(blocks),
                    "proposed_attenuation_seconds": sum(b["end_seconds"] - b["start_seconds"] for b in blocks)},
        "limits": "Acoustic blocks only. No semantic ad classification, labels, duration prior or rendered-audio validation."
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as output:
        json.dump(result, output, indent=2, allow_nan=False)
    print(json.dumps(result["summary"]))


if __name__ == "__main__":
    main()
