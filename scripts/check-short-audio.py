#!/usr/bin/env python3
"""Offline host regression with the app's exact FFmpeg/window code and public human speech.

Prerequisites: java, kotlinc, ffmpeg; pip install sherpa-onnx==1.13.2 numpy soundfile.
Supply the extracted official SenseVoice 2024-07-17 int8 archive with --model-dir.
No uploads, device state, model downloads or paid APIs. This is NOT an Android/OEM reproduction.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import resource
import subprocess
import tempfile
import time

import numpy as np
import sherpa_onnx
import soundfile as sf


def run(args):
    return subprocess.run([str(x) for x in args], check=True, capture_output=True, text=True).stdout


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--kotlinc", default="kotlinc")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    model = args.model_dir.resolve()
    report = {
        "scope": "Host FFmpeg + app Kotlin PCM/window helpers + SenseVoice; not Android or user reproduction",
        "model": model.name,
        "model_sha256": sha(model / "model.int8.onnx"),
        "runtime": sherpa_onnx.__version__,
        "threads": 2,
        "host": platform.platform(),
        "public_fixture_archive": "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        "fixtures": {name: sha(model / "test_wavs" / (name + ".wav")) for name in ("zh", "en")},
        "cases": [],
    }
    started = time.monotonic()
    recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(model / "model.int8.onnx"), tokens=str(model / "tokens.txt"),
        num_threads=2, use_itn=True, debug=False,
    )
    report["model_load_seconds"] = round(time.monotonic() - started, 3)

    def infer(samples):
        stream = recognizer.create_stream()
        stream.accept_waveform(16000, samples)
        started = time.monotonic()
        recognizer.decode_stream(stream)
        return stream.result.text, stream.result.lang, round(time.monotonic() - started, 3)

    with tempfile.TemporaryDirectory(prefix="short-asr-") as directory:
        temp = Path(directory)
        runner = temp / "PcmRunner.kt"
        runner.write_text('''import com.changyow.mediadler.core.transcribe.FfmpegPcm
import com.changyow.mediadler.core.transcribe.BoundedAudioWindows
import com.changyow.mediadler.core.transcribe.PcmSignal
import java.io.File
fun main(args: Array<String>) {
    val source = File(args[0]); val directory = File(args[1]).apply { mkdirs() }
    val duration = args[2].toLong().takeIf { it > 0 }
    val windows = BoundedAudioWindows(duration, 60000, 3000, 16000)
    var index = 0
    while (true) {
        val window = windows.windowAt(index)
        val output = File(directory, "$index.pcm")
        val process = ProcessBuilder(listOf("ffmpeg") + FfmpegPcm.arguments(
            source, output, window.startMs, window.endMs)).inheritIO().start()
        check(process.waitFor() == 0) { "FFmpeg failed" }
        val pcm = FfmpegPcm.read(output, 976000)
        val decision = windows.afterDecode(index, pcm.size)
        check(decision.action != BoundedAudioWindows.Action.MISSING_EXPECTED_AUDIO)
        if (decision.action == BoundedAudioWindows.Action.END_OF_INPUT) break
        println("$index,${window.startMs},${window.endMs},${pcm.size},${PcmSignal.isDigitalSilence(pcm)}")
        if (!decision.hasNext) break
        index++
    }
}
''')
        jar = temp / "pcm.jar"
        source = root / "core/src/main/kotlin/com/changyow/mediadler/core/transcribe"
        run([args.kotlinc, source / "FfmpegPcm.kt", source / "WindowPlanner.kt", source / "BoundedAudioWindows.kt", source / "PcmSignal.kt", runner,
             "-include-runtime", "-d", jar])
        zh, sr = sf.read(model / "test_wavs/zh.wav", dtype="float32")
        en, en_sr = sf.read(model / "test_wavs/en.wav", dtype="float32")
        assert sr == en_sr == 16000
        cases = [
            ("zh-2s", zh[:32000], "crop of public human recording", "zh", None),
            ("zh-5.592s", zh, "unmodified public human recording", "zh", None),
            ("zh-22.368s", np.tile(zh, 4), "assembled repeat of public human recording", "zh", None),
            ("zh-67.104s", np.tile(zh, 12), "assembled long/window-boundary regression, not natural long speech", "zh", None),
            ("zh-67.104s-metadata10s", np.tile(zh, 12), "same assembled recording, deliberately under-reported metadata", "zh", 10000),
            ("en-7.152s", en, "unmodified public human recording", "en", None),
            ("silence-2.2s", np.zeros(35200, dtype=np.float32), "synthetic true silence", None, None),
        ]
        for name, samples, provenance, expected_lang, metadata_override_ms in cases:
            wav, aac = temp / (name + ".wav"), temp / (name + ".m4a")
            sf.write(wav, samples, 16000, subtype="PCM_16")
            run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", wav,
                 "-ar", "22050", "-ac", "2", "-c:a", "aac", "-b:a", "64k", aac])
            assert b"elst" in aac.read_bytes(), "AAC fixture must exercise the priming edit-list container"
            baseline_text, baseline_lang, baseline_seconds = infer(samples[:60 * 16000])
            duration_ms = metadata_override_ms or len(samples) // 16
            pcm_dir = temp / name
            ranges = [line.split(",") for line in
                      run(["java", "-jar", jar, aac, pcm_dir, duration_ms]).splitlines()]
            windows = []
            for index_text, start_text, end_text, count_text, silence_text in ranges:
                index, start, end, decoded_count = map(int, (index_text, start_text, end_text, count_text))
                digital_silence = silence_text == "true"
                pcm = pcm_dir / f"{index}.pcm"
                decoded = np.fromfile(pcm, dtype="<i2").astype(np.float32) / 32768
                assert len(decoded) == decoded_count and decoded_count > 0
                # Same exact-silence predicate as the app. Keep the unguarded model result in
                # baseline_excerpt so a silence hallucination is not hidden by this regression.
                text, lang, seconds = ("", "", 0.0) if digital_silence else infer(decoded)
                if expected_lang:
                    assert text.strip(), (name, "empty transcript")
                    # Language/word accuracy is an observation, not a criterion to weaken until
                    # this clean-input decoder test passes. Keep mismatches visible in the report.
                else:
                    assert np.max(np.abs(decoded)) == 0, "Silence must remain nonempty zero PCM"
                    assert not text.strip(), (name, "model hallucinated on silence", text)
                windows.append({"start_ms": start, "requested_end_ms": end, "samples": decoded_count,
                    "rms": float(np.sqrt(np.mean(decoded ** 2))), "text_excerpt": text[:60],
                    "model_skipped_digital_silence": digital_silence,
                    "text_sha256": hashlib.sha256(text.encode()).hexdigest(), "language": lang,
                    "inference_seconds": seconds,
                    "language_matches_reference": expected_lang is None or expected_lang in lang})
            report["cases"].append({"name": name, "provenance": provenance,
                "source_seconds": len(samples) / 16000, "declared_metadata_ms": duration_ms, "aac_has_elst": True,
                "baseline_excerpt": baseline_text[:60], "baseline_language": baseline_lang,
                "baseline_inference_seconds": baseline_seconds, "windows": windows})
            assert windows, (name, "no windows accepted")
            assert windows[-1]["start_ms"] / 1000 + windows[-1]["samples"] / 16000 >= len(samples) / 16000, (name, "tail lost")
            print(name, "DECODE_PASS", [(w["samples"], w["text_excerpt"]) for w in windows], flush=True)
    report["peak_host_rss_kib"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    report["result"] = "decoder_regression_passed"
    report["quality_note"] = "Nonempty text is not an accuracy pass. Review excerpts and language mismatches; no CER benchmark is claimed."
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("Report:", args.output)


if __name__ == "__main__":
    main()
