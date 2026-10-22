package com.changyow.mediadler.transcribe

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SherpaRecognizerConfigTest {
    private val modelDir = File("models/test-sherpa")

    @Test fun multicoreRecognizersUseAtMostTwoCpuThreads() {
        for (model in SherpaModel.entries) {
            for (processors in listOf(8, 16)) {
                val config = sherpaRecognizerConfig(model, modelDir, null, processors)
                assertEquals(2, config.modelConfig.numThreads, "$model on $processors cores")
                assertEquals("cpu", config.modelConfig.provider)
            }
        }
    }

    @Test fun singleCoreRecognizersDoNotOversubscribe() {
        for (model in SherpaModel.entries) {
            val config = sherpaRecognizerConfig(model, modelDir, null, availableProcessors = 1)
            assertEquals(1, config.modelConfig.numThreads, "$model on one core")
        }
    }

    @Test fun senseVoiceKeepsItsRecognitionOptions() {
        val automatic = sherpaRecognizerConfig(SherpaModel.SENSE_VOICE, modelDir, null, 8)
        val forced = sherpaRecognizerConfig(SherpaModel.SENSE_VOICE, modelDir, "EN", 8)
        assertEquals("", automatic.modelConfig.senseVoice.language)
        assertEquals("en", forced.modelConfig.senseVoice.language)
        assertTrue(forced.modelConfig.senseVoice.useInverseTextNormalization)
        assertEquals(File(modelDir, "model.int8.onnx").absolutePath, forced.modelConfig.senseVoice.model)
        assertEquals(File(modelDir, "tokens.txt").absolutePath, forced.modelConfig.tokens)
        assertEquals(16_000, forced.featConfig.sampleRate)
        assertEquals(80, forced.featConfig.featureDim)
        assertEquals("greedy_search", forced.decodingMethod)
    }

    @Test fun otherModelsKeepTheirRequiredAssets() {
        val paraformer = sherpaRecognizerConfig(SherpaModel.PARAFORMER, modelDir, "zh", 8).modelConfig
        assertEquals(File(modelDir, "model.int8.onnx").absolutePath, paraformer.paraformer.model)
        assertEquals(File(modelDir, "tokens.txt").absolutePath, paraformer.tokens)
        val qwen = sherpaRecognizerConfig(SherpaModel.QWEN3, modelDir, null, 8).modelConfig
        assertEquals(File(modelDir, "conv_frontend.onnx").absolutePath, qwen.qwen3Asr.convFrontend)
        assertEquals(File(modelDir, "encoder.int8.onnx").absolutePath, qwen.qwen3Asr.encoder)
        assertEquals(File(modelDir, "decoder.int8.onnx").absolutePath, qwen.qwen3Asr.decoder)
        assertEquals(File(modelDir, "tokenizer").absolutePath, qwen.qwen3Asr.tokenizer)
        assertEquals("", qwen.tokens)
        assertEquals(512, qwen.qwen3Asr.maxTotalLen)
        assertEquals(256, qwen.qwen3Asr.maxNewTokens)
        assertEquals(128, sherpaRecognizerConfig(SherpaModel.QWEN3, modelDir, null, 8).featConfig.featureDim)
    }

    @Test fun fireRedUsesAedNotCtcAndDoesNotInventLanguageHint() {
        val config = sherpaRecognizerConfig(SherpaModel.FIRE_RED_ASR2, modelDir, "en", 8)
        assertEquals(File(modelDir, "encoder.int8.onnx").absolutePath, config.modelConfig.fireRedAsr.encoder)
        assertEquals(File(modelDir, "decoder.int8.onnx").absolutePath, config.modelConfig.fireRedAsr.decoder)
        assertEquals(File(modelDir, "tokens.txt").absolutePath, config.modelConfig.tokens)
        assertEquals("", config.modelConfig.fireRedAsrCtc.model)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(30, SherpaModel.FIRE_RED_ASR2.inferenceSeconds)
    }

    @Test fun funAsrHasAllGraphsAndItsOwnTokenizerDirectory() {
        val config = sherpaRecognizerConfig(SherpaModel.FUNASR_NANO, modelDir, "en", 8)
        val nano = config.modelConfig.funasrNano
        assertEquals(File(modelDir, "encoder_adaptor.int8.onnx").absolutePath, nano.encoderAdaptor)
        assertEquals(File(modelDir, "llm.int8.onnx").absolutePath, nano.llm)
        assertEquals(File(modelDir, "embedding.int8.onnx").absolutePath, nano.embedding)
        assertEquals(File(modelDir, "Qwen3-0.6B").absolutePath, nano.tokenizer)
        assertEquals("英文", nano.language)
        assertEquals(256, nano.maxNewTokens)
        assertTrue(nano.itn)
        assertEquals("", config.modelConfig.tokens)
        assertEquals(15, SherpaModel.FUNASR_NANO.inferenceSeconds)
        assertEquals(15, SherpaModel.QWEN3.inferenceSeconds)
        assertEquals(60, SherpaModel.SENSE_VOICE.inferenceSeconds)
    }

    @Test fun languageHintsFollowEachNativeFrontend() {
        assertEquals("中文", funAsrLanguageHint("zh"))
        assertEquals("日文", funAsrLanguageHint("JA"))
        assertEquals("", funAsrLanguageHint("fr"))
        assertEquals("Chinese", qwenLanguageHint("zh"))
        assertEquals("English", qwenLanguageHint("EN"))
        assertEquals(null, qwenLanguageHint(null))
        assertEquals(null, qwenLanguageHint("invalid"))
        assertEquals("zh", fireRedOutputLanguage("已有的正體中文 checkpoint"))
        assertEquals("zh", fireRedOutputLanguage("简体中文"))
        assertEquals(null, fireRedOutputLanguage("English only"))
        assertEquals(null, fireRedOutputLanguage(""))
    }

    @Test fun newDownloadsArePinnedAndComplete() {
        for (model in listOf(SherpaModel.FIRE_RED_ASR2, SherpaModel.FUNASR_NANO)) {
            assertTrue(model.archiveSha256!!.matches(Regex("[a-f0-9]{64}")))
            assertTrue(model.requiredFiles.isNotEmpty())
            assertTrue(model.requiredFiles.none { it.startsWith("/") || ".." in it })
            assertTrue(model.url.endsWith(model.archiveName + ".tar.bz2"))
        }
    }
}
