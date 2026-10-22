package com.changyow.mediadler.transcribe

import com.changyow.mediadler.core.model.OnDeviceBackend
import com.changyow.mediadler.core.model.TranscribeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelRoutingTest {
    @Test fun everyVisibleChoiceRoutesToRealDeployment() {
        for (choice in TranscribeModel.entries) {
            when (choice.backend) {
                OnDeviceBackend.WHISPER_CPP -> assertTrue(WhisperModel.of(choice).url.startsWith("https://"))
                OnDeviceBackend.SHERPA -> assertTrue(SherpaModel.of(choice).archiveName.startsWith("sherpa-onnx-"))
            }
        }
    }
    @Test fun newWhisperDownloadsAreImmutableAndDistinctFromOldCaches() {
        for (model in listOf(WhisperModel.LARGE_V3_Q5, WhisperModel.BREEZE_25_Q5)) {
            assertTrue(model.sha256!!.matches(Regex("[a-f0-9]{64}")))
            assertTrue("/resolve/main/" !in model.url)
            assertTrue(model.approxBytes > 1_000_000_000)
        }
        assertEquals(WhisperModel.entries.size, WhisperModel.entries.map { it.fileName }.toSet().size)
        assertEquals("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17", SherpaModel.SENSE_VOICE.archiveName)
        assertEquals(163_000_000, SherpaModel.SENSE_VOICE.approxBytes)
    }
}
