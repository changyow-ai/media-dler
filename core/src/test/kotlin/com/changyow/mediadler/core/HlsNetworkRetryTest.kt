package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.HlsNetworkRetry
import com.changyow.mediadler.core.download.DownloadFailurePolicy
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class HlsNetworkRetryTest {
    @Test fun permanentStatusAnywhereInNativeErrorTailStopsNetworkRetry() = runTest {
        for (status in listOf(403, 410)) {
            val failure = IOException("HTTP Error 503\nNo address associated with hostname\nHTTP Error $status")
            var calls = 0
            val thrown = assertFailsWith<IOException> {
                HlsNetworkRetry.execute({ _, _ -> error("must not retry") }) { calls++; throw failure }
            }
            assertSame(failure, thrown)
            assertEquals(1, calls)
        }
        assertEquals(0L, currentTime)
    }

    @Test fun transientDnsFailureRetriesTheSameDownloadAfterWaiting() = runTest {
        var calls = 0
        val waits = mutableListOf<Pair<Int, Long>>()
        HlsNetworkRetry.execute({ attempt, delay -> waits += attempt to delay }) {
            if (++calls < 3) throw IOException("[Errno 7] No address associated with hostname. fragment 306 not found")
        }
        assertEquals(3, calls)
        assertEquals(listOf(1 to 5_000L, 2 to 15_000L), waits)
        assertEquals(20_000L, currentTime)
    }

    @Test fun retryBudgetIsBoundedAndKeepsTheOriginalFailure() = runTest {
        val failure = IOException("Network is unreachable")
        var calls = 0
        val waits = mutableListOf<Long>()
        val thrown = assertFailsWith<IOException> {
            HlsNetworkRetry.execute({ _, delay -> waits += delay }) { calls++; throw failure }
        }
        assertSame(failure, thrown)
        assertEquals(5, calls)
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L), waits)
        assertEquals(110_000L, currentTime)
    }

    @Test fun permanentFailuresDoNotRestartOrDelay() = runTest {
        for (message in listOf("HTTP Error 403: Forbidden", "HTTP Error 404: Not Found",
            "No space left on device", "Permission denied", "HLS checkpoint does not match this session",
            "No address associated with hostname\nHTTP Error 403: Forbidden",
            "No address associated with hostname\nNo space left on device")) {
            val failure = IOException(message)
            var calls = 0
            val thrown = assertFailsWith<IOException> {
                HlsNetworkRetry.execute({ _, _ -> error("must not retry") }) { calls++; throw failure }
            }
            assertSame(failure, thrown)
            assertEquals(1, calls)
        }
        assertEquals(0L, currentTime)
    }

    @Test fun cancellationWhileWaitingCannotStartAnotherWriter() = runTest {
        var calls = 0
        val job = launch {
            HlsNetworkRetry.execute({ _, _ -> }) {
                calls++
                throw IOException("Temporary failure in name resolution")
            }
        }
        runCurrent()
        job.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun sharedNetworkClassifierHandlesWrappedExtractionAndNativeErrorsWithoutMaskingPermanentFailures() {
        for (failure in listOf(
            IllegalStateException("extract failed", java.net.UnknownHostException("cdn.example")),
            IOException("Connection refused"), IOException("HTTP Error 408"), IOException("HTTP Error 429"),
            IOException("HTTP Error 503: service unavailable"), IOException("Unable to resolve host cdn.example"),
            IOException("The read operation timed out"),
        )) assertTrue(DownloadFailurePolicy.isTransientNetworkFailure(failure), failure.toString())
        for (message in listOf("HTTP Error 401\nConnection reset", "HTTP Error 404\nHTTP Error 503",
            "HTTP Error 403\nUnable to resolve host", "HTTP Error 410\nConnection reset",
            "No space left on device\nConnection reset", "Read-only file system\nConnection reset",
            "Permission denied\nHTTP Error 503", "unsupported media format")) {
            assertFalse(DownloadFailurePolicy.isTransientNetworkFailure(IOException(message)), message)
        }
    }

    @Test fun nonNetworkFailureAndSuccessfulDownloadExecuteOnlyOnce() = runTest {
        var calls = 0
        HlsNetworkRetry.execute({ _, _ -> error("must not retry") }) { calls++ }
        assertEquals(1, calls)
        val failure = IllegalStateException("incompatible engine")
        val thrown = assertFailsWith<IllegalStateException> {
            HlsNetworkRetry.execute({ _, _ -> error("must not retry") }) { throw failure }
        }
        assertSame(failure, thrown)
    }
}
