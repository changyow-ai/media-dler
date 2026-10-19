package com.changyow.mediadler.core.sharing

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SharingActivityTest {
    private val now = AtomicLong()
    private val activity = SharingActivity(now::get)
    private val timeout = SharingIdleTimeout.THIRTY_MINUTES.millis

    private fun files(read: (ByteBuffer) -> Int = { it.put(1); 1 }) = object : SharedFiles {
        override fun stat(path: String) = SharedEntry(path, path, path == "/", 1)
        override fun list(path: String) = listOf(stat("/file"))
        override fun open(path: String) = object : SeekableByteChannel {
            private var opened = true
            override fun isOpen() = opened
            override fun close() { opened = false }
            override fun read(dst: ByteBuffer) = read(dst)
            override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
            override fun position() = 0L
            override fun position(newPosition: Long): SeekableByteChannel = this
            override fun size() = 1L
            override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
        }
    }

    private fun expired(duration: Long = timeout) = activity.expireIfIdle(duration) { true }

    @Test fun policiesAndFallback() {
        assertEquals(1_800_000L, timeout)
        assertEquals(3_600_000L, SharingIdleTimeout.ONE_HOUR.millis)
        assertEquals(SharingIdleTimeout.ONE_HOUR, SharingIdleTimeout.DEFAULT)
        assertEquals(SharingIdleTimeout.ONE_HOUR, SharingIdleTimeout.fromMinutes(-1))
        SharingIdleTimeout.entries.forEach { assertEquals(it, SharingIdleTimeout.fromMinutes(it.minutes)) }
    }

    @Test fun initialWindowExpiresAtBoundaryOnlyOnce() {
        now.set(timeout - 1)
        assertFalse(expired())
        now.incrementAndGet()
        assertTrue(expired())
        assertFalse(expired())
    }

    @Test fun metadataAndOpenWithoutReadingDoNotExtendWindow() {
        val tracked = activity.track(files())
        now.set(timeout - 1)
        tracked.stat("/file")
        tracked.list("/")
        tracked.open("/file").use { channel ->
            channel.position(0)
            channel.size()
            now.incrementAndGet()
            assertTrue(expired())
        }
    }

    @Test fun successfulReadsFromEitherSourceShareOneWindowAndFluentSeekStaysTracked() {
        val http = activity.track(files())
        val sftp = activity.track(files())
        now.set(timeout - 1)
        http.open("/file").use { assertEquals(1, it.read(ByteBuffer.allocate(1))) }
        now.set(timeout * 2 - 2)
        assertFalse(expired())
        sftp.open("/file").use { channel ->
            assertSame(channel, channel.position(0))
            assertEquals(1, channel.position(0).read(ByteBuffer.allocate(1)))
        }
        now.set(timeout * 3 - 3)
        assertFalse(expired())
        now.incrementAndGet()
        assertTrue(expired())
    }

    @Test fun zeroEofAndFailedReadsDoNotExtendWindow() {
        now.set(timeout - 1)
        for (count in listOf(0, -1)) {
            activity.track(files { count }).open("/file").use { assertEquals(count, it.read(ByteBuffer.allocate(1))) }
        }
        activity.track(files { throw IOException("unavailable") }).open("/file").use {
            assertThrows(IOException::class.java) { it.read(ByteBuffer.allocate(1)) }
        }
        now.incrementAndGet()
        assertTrue(expired())
    }

    @Test fun neverDoesNotExpireAndChangingPolicyStartsAFullWindow() {
        now.set(timeout * 100)
        assertFalse(expired(SharingIdleTimeout.NEVER.millis))
        activity.reset()
        now.addAndGet(SharingIdleTimeout.ONE_HOUR.millis - 1)
        assertFalse(expired(SharingIdleTimeout.ONE_HOUR.millis))
        now.incrementAndGet()
        assertTrue(expired(SharingIdleTimeout.ONE_HOUR.millis))
    }

    @Test fun twoHourPolicyUsesItsFullWindow() {
        val twoHours = SharingIdleTimeout.TWO_HOURS.millis
        assertEquals(7_200_000L, twoHours)
        now.set(twoHours - 1)
        assertFalse(expired(twoHours))
        now.incrementAndGet()
        assertTrue(expired(twoHours))
    }

    @Test fun rejectedStaleStopDoesNotSealSession() {
        now.set(timeout)
        assertFalse(activity.expireIfIdle(timeout) { false })
        activity.track(files()).open("/file").use { assertEquals(1, it.read(ByteBuffer.allocate(1))) }
        assertFalse(expired())
    }

    @Test fun committedExpiryRejectsLateReadUntilNewSession() {
        activity.track(files()).open("/file").use { channel ->
            now.set(timeout)
            assertTrue(expired())
            assertThrows(ClosedChannelException::class.java) { channel.read(ByteBuffer.allocate(1)) }
            activity.reset()
            assertEquals(1, channel.read(ByteBuffer.allocate(1)))
            assertFalse(expired())
        }
    }

    @Test fun inProgressReadFinishesBeforeExpiryAndStartsANewWindow() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Int> {
                activity.track(files {
                    entered.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    it.put(1)
                    1
                }).open("/file").use { it.read(ByteBuffer.allocate(1)) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            now.set(timeout * 2)
            assertFalse(expired())
            finish.countDown()
            assertEquals(1, result.get(5, TimeUnit.SECONDS))
            now.addAndGet(timeout - 1)
            assertFalse(expired())
            now.incrementAndGet()
            assertTrue(expired())
        } finally {
            finish.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun restoredBaseDoesNotStartANewTimeoutWindow() {
        val restored = SharingActivity { now.get() }
        now.set(timeout - 1)
        restored.reset(lastReadMillis = 0, sessionRevision = 42)
        assertFalse(restored.expireIfIdle(timeout) { true })
        now.incrementAndGet()
        assertTrue(restored.expireIfIdle(timeout) { true })
    }

    @Test fun onlyPositiveReadsEmitCurrentSessionRevision() {
        val reads = mutableListOf<Pair<Long, Long>>()
        val trackedActivity = SharingActivity(now::get) { revision, timestamp -> reads += revision to timestamp }
        trackedActivity.reset(sessionRevision = 8)
        now.set(100)
        val tracked = trackedActivity.track(files())
        tracked.stat("/file")
        tracked.list("/")
        tracked.open("/file").close()
        for (count in listOf(0, -1)) {
            trackedActivity.track(files { count }).open("/file").use { it.read(ByteBuffer.allocate(1)) }
        }
        trackedActivity.track(files { throw IOException("unavailable") }).open("/file").use {
            assertThrows(IOException::class.java) { it.read(ByteBuffer.allocate(1)) }
        }
        assertTrue(reads.isEmpty())
        tracked.open("/file").use { channel ->
            channel.read(ByteBuffer.allocate(1))
            trackedActivity.reset(sessionRevision = 9)
            now.set(200)
            channel.read(ByteBuffer.allocate(1))
        }
        assertEquals(listOf(8L to 100L, 9L to 200L), reads)
    }

    @Test fun resettingDuringReadSuppressesOldGenerationEvenWithSameRevision() {
        val reads = mutableListOf<Pair<Long, Long>>()
        val trackedActivity = SharingActivity(now::get) { revision, timestamp -> reads += revision to timestamp }
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            trackedActivity.reset(sessionRevision = 7)
            val result = worker.submit<Int> {
                trackedActivity.track(files {
                    entered.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    it.put(1)
                    1
                }).open("/file").use { it.read(ByteBuffer.allocate(1)) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            now.set(100)
            trackedActivity.reset(lastReadMillis = 100, sessionRevision = 7)
            now.set(100 + timeout)
            assertFalse(trackedActivity.expireIfIdle(timeout) { true }) // Preserve the in-flight guard.
            finish.countDown()
            assertEquals(1, result.get(5, TimeUnit.SECONDS))
            assertTrue(reads.isEmpty())
            assertTrue(trackedActivity.expireIfIdle(timeout) { true })
        } finally {
            finish.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun positiveReadCallbackRunsOutsideActivityMonitor() {
        val worker = Executors.newSingleThreadExecutor()
        lateinit var trackedActivity: SharingActivity
        trackedActivity = SharingActivity(now::get) { _, _ ->
            // A different thread can acquire the monitor while the callback is executing.
            worker.submit { trackedActivity.reset() }.get(5, TimeUnit.SECONDS)
        }
        try {
            trackedActivity.track(files()).open("/file").use { assertEquals(1, it.read(ByteBuffer.allocate(1))) }
        } finally {
            worker.shutdownNow()
        }
    }
}
