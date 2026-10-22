package com.changyow.mediadler.core

import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.ModelTier
import com.changyow.mediadler.core.model.OnDeviceBackend
import com.changyow.mediadler.core.model.TranscribeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscribeModelCatalogTest {
    @Test fun senseVoiceAndStoredSelectionsAreNotMigrated() {
        assertEquals(TranscribeModel.SENSE_VOICE, AppSettings().transcribeModel)
        for (name in listOf("BASE", "SMALL", "TURBO_Q5", "SENSE_VOICE", "PARAFORMER", "QWEN3")) {
            assertEquals(name, TranscribeModel.valueOf(name).name)
        }
    }
    @Test fun accuracyCandidatesAndExperimentalModelsAreDistinct() {
        assertEquals(ModelTier.COMPARISON, TranscribeModel.FIRE_RED_ASR2.tier)
        assertEquals(ModelTier.COMPARISON, TranscribeModel.BREEZE_25_Q5.tier)
        assertEquals(ModelTier.EXPERIMENTAL, TranscribeModel.FUNASR_NANO.tier)
        assertEquals(ModelTier.EXPERIMENTAL, TranscribeModel.QWEN3.tier)
        assertEquals(ModelTier.LEGACY, TranscribeModel.PARAFORMER.tier)
        assertEquals(OnDeviceBackend.WHISPER_CPP, TranscribeModel.LARGE_V3_Q5.backend)
        assertTrue(TranscribeModel.entries.all { it.sourceUrl.startsWith("https://") && it.hint.isNotBlank() })
    }
}
