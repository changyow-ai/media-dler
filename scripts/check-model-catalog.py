#!/usr/bin/env python3
"""Local model-loading/short-audio smoke check, NOT a phone benchmark or accuracy ranking.

Requires: sherpa-onnx==1.13.2, numpy, soundfile. No network requests/downloads/uploads.
Pass extracted sherpa model directories, or a GGML file plus the compiled whisper-model-smoke.
Use the same 16 kHz WAV fixtures for every model. Results include text: keep private reports local.
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

import soundfile as sf


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def recognizer(kind, model, language):
    import sherpa_onnx
    if sherpa_onnx.__version__ != "1.13.2":
        raise RuntimeError("Use the app's pinned sherpa-onnx 1.13.2")
    common = dict(num_threads=2, provider="cpu")
    file = lambda name: str(model / name)
    cls = sherpa_onnx.OfflineRecognizer
    if kind == "sensevoice":
        return cls.from_sense_voice(model=file("model.int8.onnx"), tokens=file("tokens.txt"),
                                   language=language, use_itn=True, **common)
    if kind == "fire-red-asr2":
        return cls.from_fire_red_asr(encoder=file("encoder.int8.onnx"),
                                    decoder=file("decoder.int8.onnx"), tokens=file("tokens.txt"), **common)
    if kind == "fun-asr-nano":
        return cls.from_funasr_nano(encoder_adaptor=file("encoder_adaptor.int8.onnx"),
                                   llm=file("llm.int8.onnx"), embedding=file("embedding.int8.onnx"),
                                   tokenizer=file("Qwen3-0.6B"), max_new_tokens=256, itn=True,
                                   language={"zh": "中文", "en": "英文", "ja": "日文"}.get(language, ""),
                                   **common)
    return cls.from_qwen3_asr(conv_frontend=file("conv_frontend.onnx"), encoder=file("encoder.int8.onnx"),
                             decoder=file("decoder.int8.onnx"), tokenizer=file("tokenizer"),
                             max_total_len=512, max_new_tokens=256, **common)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=["sensevoice", "fire-red-asr2", "fun-asr-nano", "qwen3-asr", "whisper"], required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, action="append", required=True)
    parser.add_argument("--language", choices=["", "zh", "en", "ja", "ko", "es", "fr", "de"], default="")
    parser.add_argument("--whisper-runner", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.kind == "whisper" and not args.whisper_runner:
        parser.error("--whisper-runner is required for GGML")
    report = dict(scope="Host smoke only; not Android, CER/WER or comparable speed ranking",
                  platform=platform.platform(), kind=args.kind, model=args.model.name,
                  language_hint=args.language or "auto", threads=2, cases=[])
    start = time.monotonic()
    native = None if args.kind == "whisper" else recognizer(args.kind, args.model, args.language)
    report["load_seconds"] = time.monotonic() - start if native else None
    for fixture in args.fixture:
        audio, rate = sf.read(fixture, dtype="float32")
        if audio.ndim == 2:
            audio = audio.mean(axis=1)
        max_seconds = 15 if args.kind in ("qwen3-asr", "fun-asr-nano") else 30
        if rate != 16000 or not 0 < len(audio) <= max_seconds * rate:
            raise ValueError(f"{fixture}: supply nonempty 16 kHz WAV of at most {max_seconds}s")
        start = time.monotonic()
        if native:
            stream = native.create_stream()
            if args.kind == "qwen3-asr" and args.language:
                stream.set_option("language", {"zh": "Chinese", "en": "English", "ja": "Japanese",
                    "ko": "Korean", "es": "Spanish", "fr": "French", "de": "German"}[args.language])
            stream.accept_waveform(rate, audio)
            native.decode_stream(stream)
            text, language = stream.result.text, stream.result.lang
            token_count = len(stream.result.tokens)
        else:
            with tempfile.TemporaryDirectory() as temp:
                pcm, output = Path(temp) / "audio.pcm", Path(temp) / "text.txt"
                audio.tofile(pcm)
                subprocess.run([str(args.whisper_runner.resolve()), str(args.model.resolve()), str(pcm),
                                args.language or "auto", str(output)], check=True, timeout=600)
                text, language, token_count = output.read_text(), None, None
        case = dict(fixture=fixture.name, sha256=digest(fixture), duration=len(audio) / rate,
                    text=text, language=language, token_count=token_count,
                    seconds=time.monotonic() - start)
        report["cases"].append(case)
        print(json.dumps(case, ensure_ascii=False), flush=True)
    # Darwin reports bytes; Linux reports KiB. Keep the report's units consistent.
    rss_divisor = 1024 if platform.system() == "Darwin" else 1
    report["max_rss_kib"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / rss_divisor
    report["max_child_rss_kib"] = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss / rss_divisor
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
