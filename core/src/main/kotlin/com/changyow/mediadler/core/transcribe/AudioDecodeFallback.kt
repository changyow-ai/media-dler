package com.changyow.mediadler.core.transcribe

import kotlinx.coroutines.CancellationException

/** An empty decoder result is not silence: real silence still has PCM samples. */
object AudioDecodeFallback {
    /** Retry once with an independent decoder, never swallow cancellation or VM errors. */
    suspend fun decode(
        primary: suspend () -> FloatArray,
        fallback: suspend () -> FloatArray,
    ): FloatArray {
        var primaryError: Exception? = null
        try {
            val samples = primary()
            if (samples.isNotEmpty()) return samples
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            primaryError = error
        }
        return try {
            fallback()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            primaryError?.let { error.addSuppressed(it) }
            throw error
        }
    }
}
