#!/usr/bin/env python3
"""Bounded deadband experiment; source audio only, no ad labels or timecodes.

Render short comparisons with exact unity below a loudness threshold, except
for the documented finite analysis/attack/release footprint around loud events.
This host prototype writes a gain signal for FFmpeg, not Android production DSP.
"""

import argparse
from array import array
import csv
import json
from pathlib import Path
import sys

import lab


def envelope(frames, ceiling, cap, duration):
    # EBU M is a trailing 400 ms measurement. Center it by 200 ms offline.
    desired = {0.0: 0.0}
    for row in frames:
        t = max(0.0, min(duration, row["t"] - 0.2))
        desired[t] = min(cap, max(0.0, row["momentary_lufs"] - ceiling))
    times = sorted(desired)
    requested = [desired[t] for t in times]
    applied = requested.copy()
    # Finite linear attack/release slopes, unlike an asymptotic gain tail.
    for i in range(len(times) - 2, -1, -1):
        applied[i] = max(applied[i], applied[i + 1] - cap / 0.1 * (times[i + 1] - times[i]))
    for i in range(1, len(times)):
        applied[i] = max(applied[i], applied[i - 1] - cap / 0.3 * (times[i] - times[i - 1]))
    assert all(0 <= gain <= cap + 1e-9 for gain in applied)
    return [(t, wanted, actual) for t, wanted, actual in zip(times, requested, applied)]


def write_gain(path, controls, rate, samples):
    """Continuous per-sample, linearly interpolated amplitude; mono float32 LE."""
    points = [(round(t * rate), 10 ** (-actual / 20)) for t, _, actual in controls]
    if points[-1][0] < samples:
        points.append((samples, points[-1][1]))
    written = 0
    with path.open("xb") as output:
        for (start, left), (end, right) in zip(points, points[1:]):
            end = min(end, samples)
            count = end - start
            if count <= 0:
                continue
            values = array("f", (left + (right - left) * i / count for i in range(count)))
            if sys.byteorder != "little":
                values.byteswap()
            values.tofile(output)
            written += count
    assert written == samples, (written, samples)


def gain_chart(controls_by_profile, duration):
    colors = {"ceiling18": "#2563eb", "ceiling19": "#d97706", "ceiling20": "#a21caf"}
    parts = ['<svg viewBox="0 0 1000 160" role="img" aria-label="Applied attenuation in dB">']
    for db in range(4):
        y = 20 + db / 3 * 110
        parts.append(f'<path d="M35,{y} H970" stroke="#ddd"/><text x="0" y="{y+4}" font-size="12">{db}</text>')
    for name, rows in controls_by_profile.items():
        points = " ".join(f"{35+t/duration*935:.1f},{20+gain/3*110:.1f}" for t, _, gain in rows)
        parts.append(f'<polyline fill="none" stroke="{colors[name]}" points="{points}"/>')
    return "".join(parts) + '</svg><p>降低幅度（dB），0 表示控制訊號不改音量；400 ms 分析窗與有限恢復時間會影響相鄰片段。</p>'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--start", type=float, default=0)
    parser.add_argument("--duration", type=float, default=70)
    parser.add_argument("--ceilings", type=float, nargs="+", choices=(-18, -19, -20), default=[-18, -19])
    args = parser.parse_args()
    source = args.input.resolve(strict=True)
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    pcm = out / "source.wav"
    # Both the control and each treated version are encoded once from the same
    # lossless float PCM. Detector analysis runs before any AAC re-encoding.
    lab.run(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-n", "-ss", str(args.start),
             "-i", str(source), "-t", str(args.duration), "-map", "0:a:0", "-c:a", "pcm_f32le", str(pcm)])
    metadata = lab.probe(pcm)
    stream = metadata["streams"][0]
    if stream["channels"] != 2:
        raise ValueError("This bounded experiment requires stereo input")
    rate = int(stream["sample_rate"])
    duration = float(stream["duration"])
    samples = round(duration * rate)
    detector, frames = lab.measure(pcm, out / "detector")
    result = {"source": str(source), "source_probe": lab.probe(source), "start_seconds": args.start,
              "duration_seconds": args.duration, "selective": True, "detector": detector,
              "ffmpeg_version": lab.run(["ffmpeg", "-version"])[0].stdout.splitlines()[0],
              "description": "高響度門檻，最多降低 3 dB；低於門檻不觸發，含 100 ms attack／300 ms release。僅供取捨實驗。",
              "variants": {}}
    series, curves = {}, {}
    profiles = [("original", None)] + [(f"ceiling{-ceiling:g}", ceiling) for ceiling in args.ceilings]
    for name, ceiling in profiles:
        print(f"Rendering {name}", flush=True)
        target = out / f"{name}.m4a"
        if ceiling is None:
            row = lab.encode(pcm, target, "anull")
        else:
            controls = curves[name] = envelope(frames, ceiling, 3, duration)
            with (out / f"{name}-gain.csv").open("w", newline="") as output:
                writer = csv.writer(output)
                writer.writerow(("t", "requested_attenuation_db", "applied_attenuation_db"))
                writer.writerows(controls)
            gain = out / f"{name}.gain.f32le"
            write_gain(gain, controls, rate, samples)
            command = ["ffmpeg", "-hide_banner", "-nostdin", "-v", "warning", "-n", "-i", str(pcm),
                       "-f", "f32le", "-ar", str(rate), "-ac", "1", "-i", str(gain),
                       "-filter_complex", "[1:a]pan=stereo|c0=c0|c1=c0[g];[0:a][g]amultiply[a]",
                       "-map", "[a]", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", str(target)]
            process, cost = lab.run(command)
            row = {"command": command, "cost": cost, "warnings": process.stderr,
                   "bytes": target.stat().st_size, "probe": lab.probe(target),
                   "control": {"ceiling_lufs": ceiling, "max_attenuation_db": 3,
                               "attack_seconds": 0.1, "release_seconds": 0.3, "lookahead_centering_seconds": 0.2}}
            gain.unlink()
        row["summary"], series[name] = lab.measure(target, out / name)
        result["variants"][name] = row
    lab.write_listening_page(out, result, series)
    page = out / "index.html"
    page.write_text(page.read_text().replace("<script>", "<h2>實際施加的控制曲線</h2>" + gain_chart(curves, duration) + "<script>", 1))
    (out / "metrics.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    pcm.unlink()
    print(json.dumps({"output": str(out)}))


if __name__ == "__main__":
    main()
