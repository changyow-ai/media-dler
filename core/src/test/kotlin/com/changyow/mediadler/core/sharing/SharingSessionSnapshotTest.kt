package com.changyow.mediadler.core.sharing

import org.junit.Assert.*
import org.junit.Test

class SharingSessionSnapshotTest {
    private val readAt = 1_000_000L
    private val interval = SharingSessionSnapshot.CHECKPOINT_INTERVAL_MILLIS
    private val snapshot = SharingSessionSnapshot.capture(
        upnpEnabled = true,
        sftpEnabled = true,
        sessionRevision = 12,
        idleTimeout = SharingIdleTimeout.ONE_HOUR,
        bootCount = 4,
        lastReadElapsedMillis = readAt,
    )

    @Test fun sameBootRestoresFixedUpperBoundUntilExactExpiry() {
        val upperBound = readAt + interval
        assertEquals(upperBound, snapshot.restoredActivityMillis(readAt, 4))
        val expiresAt = upperBound + snapshot.idleTimeout.millis
        assertEquals(upperBound, snapshot.restoredActivityMillis(expiresAt - 1, 4))
        assertNull(snapshot.restoredActivityMillis(expiresAt, 4))
        assertNull(snapshot.restoredActivityMillis(expiresAt + 1, 4))
    }

    @Test fun repeatedRestartsDoNotAddGraceOrResetTheWindow() {
        val upperBound = readAt + interval
        for (restartAt in listOf(readAt, readAt + 1, readAt + interval, readAt + 1_800_000)) {
            assertEquals(upperBound, snapshot.restoredActivityMillis(restartAt, 4))
        }
        assertNull(snapshot.restoredActivityMillis(upperBound + snapshot.idleTimeout.millis, 4))
    }

    @Test fun disabledUnknownBootWrongBootAndInvalidRevisionNeverRecover() {
        assertNull(snapshot.copy(upnpEnabled = false, sftpEnabled = false).restoredActivityMillis(readAt, 4))
        assertEquals(readAt + interval, snapshot.copy(upnpEnabled = false).restoredActivityMillis(readAt, 4))
        assertNull(snapshot.copy(bootCount = -1).restoredActivityMillis(readAt, -1))
        assertNull(snapshot.restoredActivityMillis(readAt, -1))
        assertNull(snapshot.restoredActivityMillis(readAt, 5))
        assertNull(snapshot.copy(sessionRevision = -1).restoredActivityMillis(readAt, 4))
    }

    @Test fun corruptOrFutureElapsedTimesNeverRecover() {
        assertNull(snapshot.copy(lastReadElapsedMillis = -1).restoredActivityMillis(readAt, 4))
        assertNull(snapshot.restoredActivityMillis(readAt - 1, 4))
        assertNull(snapshot.restoredActivityMillis(-1, 4))
        assertNull(snapshot.copy(recoveryReadUpperBoundElapsedMillis = readAt - 1).restoredActivityMillis(readAt, 4))
        assertNull(snapshot.copy(recoveryReadUpperBoundElapsedMillis = readAt + interval + 1).restoredActivityMillis(readAt, 4))
        assertEquals(readAt, snapshot.copy(recoveryReadUpperBoundElapsedMillis = readAt).restoredActivityMillis(readAt, 4))
    }

    @Test fun neverStillRequiresValidSameBootState() {
        val never = snapshot.copy(idleTimeout = SharingIdleTimeout.NEVER)
        assertEquals(readAt + interval, never.restoredActivityMillis(Long.MAX_VALUE, 4))
        assertNull(never.restoredActivityMillis(Long.MAX_VALUE, 5))
        assertNull(never.copy(lastReadElapsedMillis = -1).restoredActivityMillis(Long.MAX_VALUE, 4))
        assertNull(never.copy(upnpEnabled = false, sftpEnabled = false).restoredActivityMillis(readAt, 4))
    }

    @Test fun firstReadAfterLongIdleCheckpointsImmediatelyAndHighFrequencyReadsAreThrottled() {
        assertFalse(snapshot.shouldCheckpoint(readAt + interval - 1))
        assertTrue(snapshot.shouldCheckpoint(readAt + interval))
        val nextRead = readAt + 1_800_000
        assertTrue(snapshot.shouldCheckpoint(nextRead))
        val checkpoint = SharingSessionSnapshot.capture(true, true, 12, snapshot.idleTimeout, 4, nextRead)
        assertFalse(checkpoint.shouldCheckpoint(nextRead))
        assertFalse(checkpoint.shouldCheckpoint(nextRead + 1))
        assertFalse(checkpoint.shouldCheckpoint(nextRead + interval - 1))
        assertTrue(checkpoint.shouldCheckpoint(nextRead + interval))
        assertFalse(checkpoint.shouldCheckpoint(nextRead - 1))
        assertFalse(checkpoint.shouldCheckpoint(-1))
        assertFalse(checkpoint.copy(lastReadElapsedMillis = -1).shouldCheckpoint(nextRead))
    }

    @Test fun upperBoundAndExpiryArithmeticCannotOverflow() {
        val nearMax = SharingSessionSnapshot.capture(true, false, 0, SharingIdleTimeout.ONE_HOUR, 4, Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE, nearMax.recoveryReadUpperBoundElapsedMillis)
        assertEquals(Long.MAX_VALUE, nearMax.restoredActivityMillis(Long.MAX_VALUE, 4))
        assertFalse(nearMax.shouldCheckpoint(Long.MAX_VALUE))
        assertTrue(snapshot.shouldCheckpoint(Long.MAX_VALUE))
    }
}
