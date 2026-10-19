package com.changyow.mediadler.core.sharing

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel

enum class SharingIdleTimeout(val minutes: Int, val label: String) {
    THIRTY_MINUTES(30, "30 分鐘"),
    ONE_HOUR(60, "1 小時"),
    TWO_HOURS(120, "2 小時"),
    NEVER(0, "永不"),
    ;

    val millis: Long get() = minutes * 60_000L

    companion object {
        val DEFAULT = ONE_HOUR
        fun fromMinutes(minutes: Int): SharingIdleTimeout = entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}

/** One sharing session, measured with a monotonic clock; only successful file bytes count. */
class SharingActivity(
    private val nowMillis: () -> Long,
    private val onRead: (sessionRevision: Long, readElapsedMillis: Long) -> Unit = { _, _ -> },
) {
    constructor(nowMillis: () -> Long) : this(nowMillis, { _, _ -> })

    private var lastRead = nowMillis()
    private var reading = 0
    private var expired = false
    private var sessionRevision = 0L
    private var generation = Any()

    @Synchronized fun reset(lastReadMillis: Long = nowMillis(), sessionRevision: Long? = null) {
        lastRead = lastReadMillis
        this.sessionRevision = sessionRevision ?: (this.sessionRevision + 1)
        generation = Any()
        expired = false
    }

    /** The nonblocking callback must atomically stop the same session, or decline a stale decision. */
    @Synchronized fun expireIfIdle(timeoutMillis: Long, stopSession: () -> Boolean): Boolean {
        if (timeoutMillis <= 0 || expired || reading > 0 || nowMillis() - lastRead < timeoutMillis) return false
        if (!stopSession()) return false
        expired = true
        return true
    }

    fun track(files: SharedFiles): SharedFiles = object : SharedFiles by files {
        override fun open(path: String): SeekableByteChannel {
            val channel = files.open(path)
            return object : SeekableByteChannel by channel {
                override fun read(dst: ByteBuffer): Int = trackRead { channel.read(dst) }
                override fun position(newPosition: Long): SeekableByteChannel { channel.position(newPosition); return this }
                override fun truncate(size: Long): SeekableByteChannel { channel.truncate(size); return this }
            }
        }
    }

    private fun trackRead(read: () -> Int): Int {
        val readGeneration = synchronized(this) {
            if (expired) throw ClosedChannelException()
            reading++
            generation
        }
        var count = 0
        try {
            return read().also { count = it }
        } finally {
            val completedRead = synchronized(this) {
                reading--
                if (count > 0 && readGeneration === generation) {
                    lastRead = nowMillis()
                    sessionRevision to lastRead
                } else null
            }
            // Persistence may acquire the controller's lock; never call it under our monitor.
            completedRead?.let { (revision, timestamp) -> onRead(revision, timestamp) }
        }
    }
}
