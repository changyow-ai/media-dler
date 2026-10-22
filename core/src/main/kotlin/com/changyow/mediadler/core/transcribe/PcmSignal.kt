package com.changyow.mediadler.core.transcribe

object PcmSignal {
    /** Only exact digital silence, not VAD or a volume threshold that could discard quiet speech. */
    fun isDigitalSilence(samples: FloatArray): Boolean = samples.isNotEmpty() && samples.all { it == 0f }
}
