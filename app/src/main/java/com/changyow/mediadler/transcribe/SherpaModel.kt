package com.changyow.mediadler.transcribe

import com.changyow.mediadler.core.model.TranscribeModel

/**
 * A downloadable sherpa-onnx ASR model, distributed as a `.tar.bz2` on the sherpa-onnx `asr-models`
 * GitHub release. Unlike the single-file ggml models, these archives bundle several files (the ONNX
 * model(s) + tokens / tokenizer), so [SherpaModelManager] downloads then extracts them; the archive's
 * own top-level directory ([archiveName]) becomes the on-disk model dir the engine reads from.
 *
 * SenseVoice/Paraformer are non-autoregressive (whole-window decode, far faster than whisper on
 * Chinese); Qwen3-ASR is high-quality but autoregressive, large, and slower — flagged experimental.
 */
enum class SherpaModel(
    val id: String,
    /** Top-level dir inside the tarball; equals the release asset stem (without `.tar.bz2`). */
    val archiveName: String,
    /** Compressed download size, used only for the data/Wi-Fi warning and the "約 X MB" hint. */
    val approxBytes: Long,
) {
    // 2024-07-17 variant: keeps punctuation (the 2025-09-09 Cantonese-tuned rebuild drops it), which
    // the readable-sentence formatter relies on.
    SENSE_VOICE(
        "sense-voice",
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        163_000_000L,
    ),
    PARAFORMER(
        "paraformer-zh",
        "sherpa-onnx-paraformer-zh-int8-2025-10-07",
        228_300_000L,
    ),
    QWEN3(
        "qwen3-asr",
        "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        878_700_000L,
    );

    /** Download URL on the sherpa-onnx `asr-models` release. */
    val url: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archiveName.tar.bz2"

    companion object {
        /** Maps a sherpa-backed [TranscribeModel] to its model; throws for whisper-backed models. */
        fun of(choice: TranscribeModel): SherpaModel = when (choice) {
            TranscribeModel.SENSE_VOICE -> SENSE_VOICE
            TranscribeModel.PARAFORMER -> PARAFORMER
            TranscribeModel.QWEN3 -> QWEN3
            else -> error("${choice.name} is not a sherpa model")
        }
    }
}
