package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.AudioDecodeFallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AudioDecodeFallbackTest {
    @Test fun emptyPlatformDecodeRetriesExactlyOnce() = runTest {
        var retries = 0
        val recovered = floatArrayOf(0.1f, -0.2f)
        val result = AudioDecodeFallback.decode({ floatArrayOf() }, { retries++; recovered })
        assertSame(recovered, result)
        assertEquals(1, retries)
    }

    @Test fun decoderFailureCanRecoverButSilenceIsNotAReasonToRetry() = runTest {
        val silence = FloatArray(16_000)
        assertSame(silence, AudioDecodeFallback.decode({ error("codec failed") }, { silence }))
        assertSame(silence, AudioDecodeFallback.decode({ silence }, { error("must not run") }))
    }

    @Test fun emptyFallbackIsReturnedForCallerToDistinguishEofFromFailure() = runTest {
        var retries = 0
        assertContentEquals(floatArrayOf(), AudioDecodeFallback.decode({ floatArrayOf() }, { retries++; floatArrayOf() }))
        assertEquals(1, retries)
    }

    @Test fun cancellationNeverStartsTheFallbackAndDualFailureKeepsBothCauses() = runTest {
        assertFailsWith<CancellationException> {
            AudioDecodeFallback.decode({ throw CancellationException("stop") }, { error("must not run") })
        }
        val platform = IllegalStateException("platform")
        val ffmpeg = IllegalStateException("ffmpeg")
        val error = assertFailsWith<IllegalStateException> {
            AudioDecodeFallback.decode({ throw platform }, { throw ffmpeg })
        }
        assertSame(ffmpeg, error)
        assertSame(platform, error.suppressed.single())
    }
}
