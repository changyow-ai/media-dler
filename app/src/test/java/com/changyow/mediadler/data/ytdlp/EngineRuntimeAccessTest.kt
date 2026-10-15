package com.changyow.mediadler.data.ytdlp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineRuntimeAccessTest {
    @Test fun parallelExecutionsFinishBeforeUpdateStarts() {
        val access = EngineRuntimeAccess()
        val pool = Executors.newFixedThreadPool(3)
        val running = CountDownLatch(2)
        val finish = CountDownLatch(1)
        val updating = CountDownLatch(1)
        try {
            val executions = List(2) {
                pool.submit {
                    access.execute {
                        running.countDown()
                        await(finish)
                    }
                }
            }
            await(running)
            val update = pool.submit { access.update { updating.countDown() } }
            assertFalse(updating.await(100, TimeUnit.MILLISECONDS))
            finish.countDown()
            executions.forEach { it.get(5, TimeUnit.SECONDS) }
            update.get(5, TimeUnit.SECONDS)
            await(updating)
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun executionWaitsUntilArchiveUpdateFinishes() {
        val access = EngineRuntimeAccess()
        val pool = Executors.newFixedThreadPool(2)
        val updating = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executing = CountDownLatch(1)
        try {
            val update = pool.submit {
                access.update {
                    updating.countDown()
                    await(finish)
                }
            }
            await(updating)
            val execution = pool.submit { access.execute { executing.countDown() } }
            assertFalse(executing.await(100, TimeUnit.MILLISECONDS))
            finish.countDown()
            update.get(5, TimeUnit.SECONDS)
            execution.get(5, TimeUnit.SECONDS)
            await(executing)
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun failedExecutionOrUpdateReleasesAccess() {
        val access = EngineRuntimeAccess()
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertFailsWith<IllegalStateException> { access.execute { error("download failed") } }
            pool.submit { access.update {} }.get(5, TimeUnit.SECONDS)
            assertFailsWith<IllegalStateException> { access.update { error("update failed") } }
            pool.submit { access.execute {} }.get(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun interruptedWaitingExecutionNeverStartsAndDoesNotLeakAccess() {
        val access = EngineRuntimeAccess()
        val entered = AtomicBoolean(false)
        val waiting = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val execution = Thread {
            waiting.countDown()
            try {
                access.execute { entered.set(true) }
            } catch (_: InterruptedException) {
                // Cancellation while waiting must not execute against an updating archive.
            } finally {
                finished.countDown()
            }
        }
        try {
            access.update {
                execution.start()
                await(waiting)
                execution.interrupt()
                await(finished)
                assertFalse(entered.get())
            }
            access.update { access.execute { entered.set(true) } }
            assertTrue(entered.get())
        } finally {
            execution.interrupt()
            execution.join(5_000)
        }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for runtime access")
    }
}
