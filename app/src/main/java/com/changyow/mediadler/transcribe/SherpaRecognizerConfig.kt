package com.changyow.mediadler.transcribe

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/** Builds the configuration passed to the native recognizer without loading the native library. */
internal fun sherpaRecognizerConfig(
    model: SherpaModel,
    dir: File,
    knownLanguage: String?,
    availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
): OfflineRecognizerConfig {
    // OEM CPU guards can kill even a foreground service during sustained all-core inference.
    // Keep this budget for the recognizer's lifetime, including when the user leaves the UI.
    val mc = OfflineModelConfig(numThreads = availableProcessors.coerceIn(1, 2), provider = "cpu")
    when (model) {
        SherpaModel.SENSE_VOICE -> {
            mc.senseVoice = OfflineSenseVoiceModelConfig(
                model = File(dir, "model.int8.onnx").absolutePath,
                language = senseVoiceLanguageHint(knownLanguage),
                useInverseTextNormalization = true,
            )
            mc.tokens = File(dir, "tokens.txt").absolutePath
        }
        SherpaModel.PARAFORMER -> {
            mc.paraformer = OfflineParaformerModelConfig(
                model = File(dir, "model.int8.onnx").absolutePath,
            )
            mc.tokens = File(dir, "tokens.txt").absolutePath
        }
        SherpaModel.QWEN3 -> {
            // Qwen3-ASR has a conv frontend, encoder, decoder and tokenizer instead of tokens.txt.
            mc.qwen3Asr = OfflineQwen3AsrModelConfig(
                convFrontend = File(dir, "conv_frontend.onnx").absolutePath,
                encoder = File(dir, "encoder.int8.onnx").absolutePath,
                decoder = File(dir, "decoder.int8.onnx").absolutePath,
                tokenizer = File(dir, "tokenizer").absolutePath,
            )
        }
    }
    return OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = AudioToPcm.TARGET_SAMPLE_RATE, featureDim = 80),
        modelConfig = mc,
        decodingMethod = "greedy_search",
    )
}

/** Maps a forced ISO language to a SenseVoice hint; unsupported/auto uses SenseVoice auto. */
internal fun senseVoiceLanguageHint(knownLanguage: String?): String = when (knownLanguage?.lowercase()) {
    "zh" -> "zh"
    "en" -> "en"
    "ja" -> "ja"
    "ko" -> "ko"
    "yue" -> "yue"
    else -> ""
}
