package com.changyow.mediadler.core.sharing

/** Durable state for recovering the same sharing session, never a new idle window. */
data class SharingSessionSnapshot(
    val upnpEnabled: Boolean,
    val sftpEnabled: Boolean,
    val sessionRevision: Long,
    val idleTimeout: SharingIdleTimeout,
    val bootCount: Int,
    val lastReadElapsedMillis: Long,
    val recoveryReadUpperBoundElapsedMillis: Long,
) {
    /** A saved upper bound gives at most one checkpoint interval of grace across all restarts. */
    fun restoredActivityMillis(nowElapsedMillis: Long, currentBootCount: Int): Long? {
        if (!upnpEnabled && !sftpEnabled) return null
        if (bootCount < 0 || currentBootCount != bootCount || sessionRevision < 0) return null
        if (lastReadElapsedMillis < 0 || nowElapsedMillis < lastReadElapsedMillis) return null
        if (recoveryReadUpperBoundElapsedMillis < lastReadElapsedMillis ||
            recoveryReadUpperBoundElapsedMillis > upperBound(lastReadElapsedMillis)
        ) return null
        val timeout = idleTimeout.millis
        if (timeout > 0 && nowElapsedMillis >= recoveryReadUpperBoundElapsedMillis &&
            nowElapsedMillis - recoveryReadUpperBoundElapsedMillis >= timeout
        ) return null
        return recoveryReadUpperBoundElapsedMillis
    }

    /** Call on a positive byte read; the first read after a long idle period checkpoints immediately. */
    fun shouldCheckpoint(readElapsedMillis: Long): Boolean =
        lastReadElapsedMillis >= 0 && readElapsedMillis >= lastReadElapsedMillis &&
            readElapsedMillis - lastReadElapsedMillis >= CHECKPOINT_INTERVAL_MILLIS

    companion object {
        const val CHECKPOINT_INTERVAL_MILLIS = 60_000L

        fun capture(
            upnpEnabled: Boolean,
            sftpEnabled: Boolean,
            sessionRevision: Long,
            idleTimeout: SharingIdleTimeout,
            bootCount: Int,
            lastReadElapsedMillis: Long,
        ) = SharingSessionSnapshot(
            upnpEnabled,
            sftpEnabled,
            sessionRevision,
            idleTimeout,
            bootCount,
            lastReadElapsedMillis,
            upperBound(lastReadElapsedMillis),
        )

        private fun upperBound(timestamp: Long): Long =
            if (timestamp > Long.MAX_VALUE - CHECKPOINT_INTERVAL_MILLIS) Long.MAX_VALUE
            else timestamp + CHECKPOINT_INTERVAL_MILLIS
    }
}
