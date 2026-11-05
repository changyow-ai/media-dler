package com.changyow.mediadler.core.sharing

import org.junit.Assert.*
import org.junit.Test

class WebShareSessionTest {
    @Test fun exactAllowedLifetimesAndTenMinuteDefault() {
        assertEquals(listOf(5, 10, 15, 20, 25, 30, 60), WebShareSession.DURATIONS_MINUTES)
        assertEquals(10, WebShareSession.DEFAULT_MINUTES)
        for (minutes in WebShareSession.DURATIONS_MINUTES) {
            val session = WebShareSession.create(minutes, 1_000, 4)
            assertEquals(1_000 + minutes * 60_000L, session.expiresAtElapsedMillis)
            assertTrue(session.isValid(session.expiresAtElapsedMillis - 1, 4))
            assertFalse(session.isValid(session.expiresAtElapsedMillis, 4))
        }
    }

    @Test fun rebootTimeRollbackAndCorruptSessionsNeverRestore() {
        val session = WebShareSession.create(5, 1_000, 4)
        assertFalse(session.isValid(1_000, 5))
        assertFalse(session.isValid(999, 4))
        assertFalse(session.copy(bootCount = -1).isValid(1_000, -1))
        assertFalse(session.copy(expiresAtElapsedMillis = 2_000).isValid(1_000, 4))
        assertFalse(session.copy(expiresAtElapsedMillis = Long.MAX_VALUE).isValid(1_000, 4))
    }

    @Test fun invalidOrExcessiveLifetimesAreRejected() {
        for (minutes in listOf(-1, 0, 1, 6, 35, 59, 61, 120)) {
            try { WebShareSession.create(minutes, 1_000, 4); fail("Accepted $minutes minutes") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
