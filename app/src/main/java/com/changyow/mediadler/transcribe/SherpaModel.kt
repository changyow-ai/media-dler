package com.changyow.mediadler.transcribe

import com.changyow.mediadler.core.model.TranscribeModel

/**
 * A downloadable sherpa-onnx ASR model, distributed as a `.tar.bz2` on the sherpa-onnx `asr-models`
 * GitHub release. Unlike the single-file ggml models, these archives bundle several files (the ONNX
 * model(s) + tokens / tokenizer), so [SherpaModelManager] downloads then extracts them; the archive's
 * own top-level directory ([archiveName]) becomes the on-disk model dir the engine reads from.
 *
 * Every entry has a native adapter in the pinned sherpa-onnx 1.13.2 runtime. New deployments are
 * checksum-pinned; inference limits are independent of the durable 60-second checkpoint windows.
 */
enum class SherpaModel(
    val id: String,
    /** Top-level dir inside the tarball; equals the release asset stem (without `.tar.bz2`). */
    val archiveName: String,
    /** Compressed download size, used only for the data/Wi-Fi warning and the "約 X MB" hint. */
    val approxBytes: Long,
    val archiveSha256: String? = null,
    val requiredFiles: List<String> = emptyList(),
    /** Hard cap for one native decode, not the checkpoint/resume window size. */
    val inferenceSeconds: Int = 60,
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
        // Keep the existing archive and download cache. Only bound the native context length.
        inferenceSeconds = 15,
    ),
    FIRE_RED_ASR2(
        "fire-red-asr2-aed-int8",
        "sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26",
        838_589_068L,
        "43015b3f1643a5688b4821e8ed323473d38b798c4ec291471fe00df1bcfc4f1c",
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
        inferenceSeconds = 30,
    ),
    FUNASR_NANO(
        "fun-asr-nano-2512-int8",
        "sherpa-onnx-funasr-nano-int8-2025-12-30",
        841_730_611L,
        "eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b",
        listOf("encoder_adaptor.int8.onnx", "llm.int8.onnx", "embedding.int8.onnx",
            "Qwen3-0.6B/merges.txt", "Qwen3-0.6B/tokenizer.json", "Qwen3-0.6B/vocab.json"),
        inferenceSeconds = 15,
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
            TranscribeModel.FIRE_RED_ASR2 -> FIRE_RED_ASR2
            TranscribeModel.FUNASR_NANO -> FUNASR_NANO
            else -> error("${choice.name} is not a sherpa model")
        }
    }
}
