#!/usr/bin/env python3
"""Offline audio-leveling experiment. Requires Python 3 and FFmpeg/ffprobe.

This is a listening/measurement prototype, not a download postprocessor.
Processing uses audio levels only. It accepts no ad labels or ad timecodes.
"""

import argparse
import csv
import html
import json
from pathlib import Path
import re
import resource
import subprocess
import time


PROFILES = ("original", "peak", "rms2", "rms4", "leveler")


def run(command):
    before = resource.getrusage(resource.RUSAGE_CHILDREN)
    started = time.monotonic()
    process = subprocess.run(command, capture_output=True, text=True)
    after = resource.getrusage(resource.RUSAGE_CHILDREN)
    cost = {
        "wall_seconds": round(time.monotonic() - started, 3),
        "cpu_seconds": round(after.ru_utime + after.ru_stime - before.ru_utime - before.ru_stime, 3),
    }
    if process.returncode:
        raise RuntimeError(f"Command failed: {command!r}\n{process.stderr[-5000:]}")
    return process, cost


def probe(source):
    result, _ = run(["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(source)])
    return json.loads(result.stdout)


def measure(source, stem):
    command = ["ffmpeg", "-hide_banner", "-nostdin", "-i", str(source),
               "-map", "0:a:0", "-af", "ebur128=peak=sample+true", "-f", "null", "-"]
    process, cost = run(command)
    stem.with_suffix(".ffmpeg.log").write_text(process.stderr)
    frames = []
    pattern = re.compile(r"\bt:\s*([\d.]+)\s+TARGET:.*?\bM:\s*([-\d.]+)\s+S:\s*([-\d.]+)")
    for match in pattern.finditer(process.stderr):
        frames.append(dict(zip(("t", "momentary_lufs", "shortterm_lufs"), map(float, match.groups()))))
    if not frames:
        raise RuntimeError("FFmpeg emitted no EBU R128 frames; inspect the saved log")
    summary_text = process.stderr.rsplit("Summary:", 1)[1]
    summary = {}
    for key, pattern in {
        "integrated_lufs": r"\bI:\s*([-\d.]+) LUFS",
        "loudness_range_lu": r"\bLRA:\s*([-\d.]+) LU",
        "sample_peak_dbfs": r"Sample peak:\s*Peak:\s*([-\d.]+) dBFS",
        "true_peak_dbtp": r"True peak:\s*Peak:\s*([-\d.]+) dBFS",
    }.items():
        match = re.search(pattern, summary_text)
        summary[key] = float(match[1]) if match else None
    # This gate only excludes quiet windows for descriptive statistics. It does
    # not label dialogue, classify ads, or control any of the processing filters.
    active = sorted(row["shortterm_lufs"] for row in frames if row["t"] >= 3 and row["shortterm_lufs"] > -50)
    summary["active_shortterm_percentiles_lufs"] = {
        str(percent): round(active[round((len(active) - 1) * percent / 100)], 1)
        for percent in (10, 25, 50, 75, 90, 95) if active
    }
    summary["measurement_cost"] = cost
    with stem.with_suffix(".csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=("t", "momentary_lufs", "shortterm_lufs"))
        writer.writeheader()
        writer.writerows(frames)
    return summary, frames


def filter_for(profile, threshold_db, peak_db):
    if profile == "original":
        return "anull"
    if profile == "peak":
        return f"alimiter=limit={10 ** (peak_db / 20):.9f}:level=0:latency=1"
    if threshold_db is None:
        raise ValueError("RMS experiments require an explicit --threshold-db; no production default is implied")
    if profile == "leveler":
        # Coupled channels, no gain above unity, no waveform compression. The
        # target applies to 250 ms frame RMS, smoothed over five frames.
        return f"dynaudnorm=f=250:g=5:m=1:r={10 ** (threshold_db / 20):.9f}:n=1:c=0"
    ratio = {"rms2": 2, "rms4": 4}[profile]
    return (f"acompressor=mode=downward:threshold={10 ** (threshold_db / 20):.9f}"
            f":ratio={ratio}:attack=10:release=700:knee=2:makeup=1:detection=rms:link=maximum")


def encode(source, target, audio_filter, start=None, duration=None, video=False):
    if source.resolve() == target.resolve() or target.exists():
        raise ValueError(f"Refusing to overwrite source or existing output: {target}")
    command = ["ffmpeg", "-hide_banner", "-nostdin", "-v", "warning", "-n"]
    if start is not None:
        command += ["-ss", str(start)]
    command += ["-i", str(source)]
    if duration is not None:
        command += ["-t", str(duration)]
    if video:
        command += ["-map", "0:v:0?", "-c:v", "copy", "-map_metadata", "0", "-map_chapters", "0"]
    command += ["-map", "0:a:0", "-af", audio_filter, "-c:a", "aac", "-b:a", "160k",
                "-movflags", "+faststart", str(target)]
    process, cost = run(command)
    return {"command": command, "cost": cost, "bytes": target.stat().st_size,
            "warnings": process.stderr, "probe": probe(target)}


def chart(series):
    colors = {"original": "#334155", "peak": "#d97706", "rms2": "#2563eb", "rms4": "#a21caf", "leveler": "#059669",
              "ceiling18": "#2563eb", "ceiling19": "#d97706", "ceiling20": "#a21caf"}
    width, height, padding = 1000, 240, 35
    duration = max(rows[-1]["t"] for rows in series.values())
    parts = [f'<svg viewBox="0 0 {width} {height}" role="img" aria-label="Short-term loudness comparison">']
    for level in (-45, -35, -25, -15, -5):
        y = padding + (-5 - level) / 45 * (height - 2 * padding)
        parts.append(f'<path d="M{padding},{y} H{width-padding}" stroke="#ddd"/>')
        parts.append(f'<text x="0" y="{y+4}" font-size="11">{level}</text>')
    for profile, rows in series.items():
        points = []
        for row in rows[::5]:
            if row["t"] < 3:
                continue
            x = padding + row["t"] / duration * (width - 2 * padding)
            y = padding + (-5 - max(-50, min(-5, row["shortterm_lufs"]))) / 45 * (height - 2 * padding)
            points.append(f"{x:.1f},{y:.1f}")
        parts.append(f'<polyline fill="none" stroke="{colors[profile]}" stroke-width="1.5" points="{" ".join(points)}"/>')
    parts.append(f'<text x="{padding}" y="{height-3}" font-size="12">0 → {duration:.1f} seconds · short-term LUFS</text></svg>')
    return "".join(parts)


def write_listening_page(out, result, series):
    labels = {"original": "原版（同樣重編 AAC，作公平比較）", "peak": "只限制峰值",
              "rms2": "輕壓縮 2:1", "rms4": "強壓縮 4:1", "leveler": "慢速音量調節（只降低）",
              "ceiling18": "保守門檻 −18 LUFS", "ceiling19": "門檻 −19 LUFS", "ceiling20": "門檻 −20 LUFS"}
    cards = []
    for profile, row in result["variants"].items():
        summary = row["summary"]
        cards.append(f'<section><h2>{html.escape(labels.get(profile, profile))}</h2><audio controls preload="metadata" src="{profile}.m4a"></audio>'
                     f'<p>I {summary["integrated_lufs"]} LUFS · true peak {summary["true_peak_dbtp"]} dBTP</p></section>')
    page = '''<!doctype html><html lang="zh-Hant"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>音量落差試聽</title>
<style>body{font:16px system-ui;max-width:1040px;margin:32px auto;padding:0 20px;color:#172033;background:#f8fafc}section{background:white;border:1px solid #dbe2ea;border-radius:10px;padding:16px;margin:12px 0}h2{font-size:18px}audio{width:100%}button{padding:10px 16px;margin-right:8px}svg{width:100%;background:white}p{line-height:1.6}code{overflow-wrap:anywhere}</style>
<h1>音量落差實驗</h1><p>所有版本使用相同音量播放。先聽原版，再切換；「同步位置」會把其他版本移到剛才的位置。處理不讀取廣告標記，也不會自動提高安靜片段。</p>
<p><button id="sync">同步位置</button><button id="stop">全部暫停</button></p>'''
    legend = "灰＝原版、藍＝−18 LUFS、橘＝−19 LUFS、紫＝−20 LUFS，最多降低 3 dB。" if result.get("selective") else "灰＝原版、橘＝峰值限制、藍＝輕壓縮、紫＝強壓縮、綠＝慢速調節。"
    page += "".join(cards) + f'<p>曲線：{legend}</p>' + chart(series)
    description = result.get("description", f'門檻 {result.get("threshold_dbfs")} dBFS，僅供本次實驗。')
    page += f'<p>來源：<code>{html.escape(result["source"])}</code>；擷取起點 {result["start_seconds"]} 秒，長度 {result["duration_seconds"]} 秒。{html.escape(description)}</p>'
    page += '''<script>const players=[...document.querySelectorAll('audio')];let last=players[0];players.forEach(p=>p.addEventListener('play',()=>{players.forEach(q=>{if(q!==p)q.pause()});last=p}));document.querySelector('#sync').onclick=()=>players.forEach(p=>{if(p!==last)p.currentTime=last.currentTime});document.querySelector('#stop').onclick=()=>players.forEach(p=>p.pause());</script></html>'''
    (out / "index.html").write_text(page)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("scan", "compare", "apply"))
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path, help="New directory (scan/compare), or new MP4 file (apply)")
    parser.add_argument("--start", type=float, default=0)
    parser.add_argument("--duration", type=float, default=70)
    parser.add_argument("--threshold-db", type=float)
    parser.add_argument("--peak-db", type=float, default=-6)
    parser.add_argument("--profile", choices=PROFILES, default="leveler")
    parser.add_argument("--profiles", nargs="+", choices=PROFILES, default=PROFILES)
    args = parser.parse_args()
    source = args.input.resolve(strict=True)
    output = args.output.resolve()
    if args.action == "apply":
        output.parent.mkdir(parents=True, exist_ok=True)
        result = encode(source, output, filter_for(args.profile, args.threshold_db, args.peak_db), video=True)
        result["summary"], _ = measure(output, output.with_suffix(".measurement"))
        output.with_suffix(".json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print(json.dumps({"output": str(output), "cost": result["cost"], "summary": result["summary"]}))
        return
    output.mkdir(parents=True, exist_ok=False)
    result = {"source": str(source), "source_probe": probe(source),
              "ffmpeg_version": run(["ffmpeg", "-version"])[0].stdout.splitlines()[0]}
    if args.action == "scan":
        result["summary"], _ = measure(source, output / "source")
    else:
        if args.threshold_db is None:
            parser.error("compare requires --threshold-db, chosen after examining a scan")
        result.update(start_seconds=args.start, duration_seconds=args.duration,
                      threshold_dbfs=args.threshold_db, peak_ceiling_dbfs=args.peak_db, variants={})
        series = {}
        for profile in args.profiles:
            print(f"Rendering {profile}", flush=True)
            row = encode(source, output / f"{profile}.m4a", filter_for(profile, args.threshold_db, args.peak_db), args.start, args.duration)
            row["summary"], series[profile] = measure(output / f"{profile}.m4a", output / profile)
            result["variants"][profile] = row
        write_listening_page(output, result, series)
    (output / "metrics.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({"output": str(output), "summary": result.get("summary")}))


if __name__ == "__main__":
    main()
