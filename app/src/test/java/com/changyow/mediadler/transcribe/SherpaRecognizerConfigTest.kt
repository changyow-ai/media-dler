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
    }
}
