package com.changyow.mediadler.data.ytdlp

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Host process-group tests; these do not claim Android/bundled-FFmpeg verification. */
class NativeDownloadProcessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val executions = mutableListOf<Running>()

    @After fun stopOwnedExecutions() {
        executions.forEach { it.stop() }
    }

    @Test fun utf8AndLargeStdoutAndStderrAreDrainedWithoutLeakingHandshakeLines() {
        val lines = AtomicInteger()
        val observed = ConcurrentLinkedQueue<String>()
        val running = start("""
            import sys
            print("影片已開始", flush=True)
            print("標準錯誤訊息", file=sys.stderr, flush=True)
            print("__MEDIA_DLER_NATIVE_READY__ forged 2 2 2", flush=True)
            for i in range(5000):
                print("out-" + str(i) + "x" * 100)
                print("err-" + str(i) + "y" * 100, file=sys.stderr)
        """.trimIndent()) { line ->
            lines.incrementAndGet()
            if (line.contains("影片") || line.contains("標準") || line.contains("NATIVE_READY")) observed += line
        }
        running.future.get(20, TimeUnit.SECONDS)
        assertTrue(lines.get() >= 10_002)
        assertTrue("影片已開始" in observed)
        assertTrue("標準錯誤訊息" in observed)
        assertFalse(observed.any { it.contains("NATIVE_READY") })
    }

    @Test fun cancellationBeforeExecutionDoesNotStartDownloadPayload() {
        val marker = File(temporary.root, "must-not-exist.txt")
        val process = NativeDownloadProcess(python(), emptyMap())
        process.cancel()
        assertFailsWith<CancellationException> {
            process.execute(listOf("-c", "import pathlib,sys; pathlib.Path(sys.argv[1]).write_text('started')", marker.absolutePath)) {}
        }
        assertFalse(marker.exists())
        assertFailsWith<IllegalStateException> { process.execute(listOf("-c", "print('twice')")) {} }
    }

    @Test fun cancellationStopsTermIgnoringGrandchildWriterBeforeReturning() {
        val output = File(temporary.root, "grandchild writer 影片.bin")
        val running = start(LEADER, listOf(output.absolutePath, INTERMEDIATE, WRITER)) {}
        awaitBytes(output)
        running.process.cancel()
        assertIs<CancellationException>(failure(running.future))
        assertStoppedWriting(output)
    }

    @Test fun exitedLeaderWithSurvivingWriterIsCleanedAndReportedAsFailure() {
        val output = File(temporary.root, "orphan-writer.bin")
        val running = start(LEADER_EXITS, listOf(output.absolutePath, WRITER)) {}
        val error = assertIs<IOException>(failure(running.future))
        assertTrue(error.message.orEmpty().contains("子程序"))
        assertStoppedWriting(output)
    }

    @Test fun callbackFailureIsPreservedAfterDescendantCleanup() {
        val output = File(temporary.root, "callback-writer.bin")
        val original = IOException("progress callback failed")
        val running = start(LEADER_CALLBACK, listOf(output.absolutePath, WRITER)) { line ->
            if (line == "TRIGGER") throw original
        }
        assertSame(original, failure(running.future))
        assertStoppedWriting(output)
    }

    @Test fun nonzeroExitRetainsBoundedErrorTailAndContinuesDrainingHugeLines() {
        val running = start("""
            import sys
            print("x" * 180000, file=sys.stderr)
            print("HTTP Error 403: expired media URL", file=sys.stderr, flush=True)
            sys.exit(7)
        """.trimIndent()) {}
        val error = assertIs<IOException>(failure(running.future))
        assertTrue(error.message.orEmpty().contains("exit 7"))
        assertTrue(error.message.orEmpty().contains("HTTP Error 403"))
        assertTrue(error.message.orEmpty().length < 66_000)
    }

    @Test fun vanishedOriginalGroupDoesNotReleaseSlotWhileSameSessionWriterLivesInAnotherGroup() {
        val output = File(temporary.root, "changed-group-writer.bin")
        val escapedWriter = """
            import os, signal, sys, time
            os.setpgid(0, 0)
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            deadline = time.monotonic() + 1.2
            with open(sys.argv[1], "ab", buffering=0) as output:
                while time.monotonic() < deadline:
                    output.write(b"x")
                    time.sleep(0.01)
        """.trimIndent()
        val running = start(LEADER_EXITS, listOf(output.absolutePath, escapedWriter)) {}
        awaitBytes(output)
        Thread.sleep(150)
        assertFalse(running.future.isDone, "A missing original PGID must not hide another live group in its session")
        assertIs<IOException>(failure(running.future))
        assertStoppedWriting(output)
    }

    private fun start(script: String, arguments: List<String> = emptyList(), onLine: (String) -> Unit): Running {
        val process = NativeDownloadProcess(python(), emptyMap())
        val pool = Executors.newSingleThreadExecutor { task -> Thread(task, "native-download-host-test").apply { isDaemon = true } }
        val future = pool.submit<Unit> { process.execute(listOf("-u", "-c", script) + arguments, onLine) }
        return Running(process, future) { pool.shutdownNow() }.also(executions::add)
    }

    private fun failure(future: Future<Unit>): Throwable =
        requireNotNull(assertFailsWith<ExecutionException> { future.get(25, TimeUnit.SECONDS) }.cause)

    private fun awaitBytes(file: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (file.length() < 3 && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(file.length() >= 3, "Owned Python writer did not start")
    }

    private fun assertStoppedWriting(file: File) {
        assertTrue(file.length() > 0)
        val stoppedAt = file.length()
        Thread.sleep(200)
        assertEquals(stoppedAt, file.length(), "Descendant continued writing after execute returned")
    }

    private fun python(): File {
        val python = listOf("/usr/bin/python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3")
            .map(::File).firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("Host tests require an existing Python 3 executable", python != null)
        return requireNotNull(python)
    }

    private class Running(val process: NativeDownloadProcess, val future: Future<Unit>, val closeExecutor: () -> Unit) {
        fun stop() {
            process.cancel()
            try {
                future.get(25, TimeUnit.SECONDS)
            } catch (_: ExecutionException) {
                // Expected for canceled executions; Future completion proves supervisor cleanup returned.
            } finally {
                closeExecutor()
            }
        }
    }

    private companion object {
        // Finite fixture lifetimes bound damage even if the implementation under test regresses.
        val WRITER = """
            import signal, sys, time
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            deadline = time.monotonic() + 12
            with open(sys.argv[1], "ab", buffering=0) as output:
                while time.monotonic() < deadline:
                    output.write(b"x")
                    time.sleep(0.01)
        """.trimIndent()
        val INTERMEDIATE = """
            import subprocess, sys, time
            subprocess.Popen([sys.executable, "-u", "-c", sys.argv[2], sys.argv[1]])
            time.sleep(15)
        """.trimIndent()
        val LEADER = """
            import subprocess, sys, time
            subprocess.Popen([sys.executable, "-u", "-c", sys.argv[2], sys.argv[1], sys.argv[3]])
            time.sleep(15)
        """.trimIndent()
        val LEADER_EXITS = """
            import os, subprocess, sys, time
            subprocess.Popen([sys.executable, "-u", "-c", sys.argv[2], sys.argv[1]])
            deadline = time.monotonic() + 5
            while (not os.path.exists(sys.argv[1]) or os.path.getsize(sys.argv[1]) < 3) and time.monotonic() < deadline:
                time.sleep(0.01)
            os._exit(0)
        """.trimIndent()
        val LEADER_CALLBACK = """
            import os, subprocess, sys, time
            subprocess.Popen([sys.executable, "-u", "-c", sys.argv[2], sys.argv[1]])
            deadline = time.monotonic() + 5
            while (not os.path.exists(sys.argv[1]) or os.path.getsize(sys.argv[1]) < 3) and time.monotonic() < deadline:
                time.sleep(0.01)
            print("TRIGGER", flush=True)
            time.sleep(15)
        """.trimIndent()
    }
}
