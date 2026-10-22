package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.PcmSignal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcmSignalTest {
    @Test fun onlyNonemptyDigitalSilenceBypassesRecognition() {
        assertTrue(PcmSignal.isDigitalSilence(FloatArray(35_200)))
        assertTrue(PcmSignal.isDigitalSilence(floatArrayOf(0f, -0f)))
        assertFalse(PcmSignal.isDigitalSilence(floatArrayOf()))
    }

    @Test fun veryQuietAudioIsNeverDroppedByAnArbitraryThreshold() {
        assertFalse(PcmSignal.isDigitalSilence(floatArrayOf(0f, Float.MIN_VALUE, 0f)))
        assertFalse(PcmSignal.isDigitalSilence(floatArrayOf(0f, -0.00001f, 0f)))
    }
}
