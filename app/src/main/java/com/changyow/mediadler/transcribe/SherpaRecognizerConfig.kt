package com.changyow.mediadler.transcribe

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
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
                maxTotalLen = 512,
                maxNewTokens = 256,
            )
        }
        SherpaModel.FIRE_RED_ASR2 -> {
            mc.fireRedAsr = OfflineFireRedAsrModelConfig(
                encoder = File(dir, "encoder.int8.onnx").absolutePath,
                decoder = File(dir, "decoder.int8.onnx").absolutePath,
            )
            mc.tokens = File(dir, "tokens.txt").absolutePath
        }
        SherpaModel.FUNASR_NANO -> {
            mc.funasrNano = OfflineFunAsrNanoModelConfig(
                encoderAdaptor = File(dir, "encoder_adaptor.int8.onnx").absolutePath,
                llm = File(dir, "llm.int8.onnx").absolutePath,
                embedding = File(dir, "embedding.int8.onnx").absolutePath,
                tokenizer = File(dir, "Qwen3-0.6B").absolutePath,
                language = funAsrLanguageHint(knownLanguage),
                maxNewTokens = 256,
                itn = true,
            )
        }
    }
    return OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = AudioToPcm.TARGET_SAMPLE_RATE,
            featureDim = if (model == SherpaModel.QWEN3) 128 else 80),
        modelConfig = mc,
        decodingMethod = "greedy_search",
    )
}

/** Fun-ASR-Nano's prompt uses language names, not Whisper ISO codes. */
internal fun funAsrLanguageHint(knownLanguage: String?): String = when (knownLanguage?.lowercase()) {
    "zh" -> "中文"
    "en" -> "英文"
    "ja" -> "日文"
    else -> ""
}

/** Canonical Qwen-ASR stream option values. Unsupported choices stay automatic. */
internal fun qwenLanguageHint(knownLanguage: String?): String? = when (knownLanguage?.lowercase()) {
    "zh" -> "Chinese"
    "en" -> "English"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "es" -> "Spanish"
    "fr" -> "French"
    "de" -> "German"
    else -> null
}

/** AED is Chinese/English only and has no LID field. Script is for formatting, not a forced hint. */
internal fun fireRedOutputLanguage(text: String): String? =
    if (text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) "zh" else null

/** Maps a forced ISO language to a SenseVoice hint; unsupported/auto uses SenseVoice auto. */
internal fun senseVoiceLanguageHint(knownLanguage: String?): String = when (knownLanguage?.lowercase()) {
    "zh" -> "zh"
    "en" -> "en"
    "ja" -> "ja"
    "ko" -> "ko"
    "yue" -> "yue"
    else -> ""
}
