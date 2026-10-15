package com.changyow.mediadler.data.ytdlp

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One execution, including its inherited process group and both output readers. */
class NativeDownloadProcess(
    private val pythonExecutable: File,
    private val environment: Map<String, String>,
    private val startupTimeoutMillis: Long = 15_000,
    private val termGraceMillis: Long = 250,
) {
    init {
        require(startupTimeoutMillis > 0)
        require(termGraceMillis >= 0)
    }

    private val lock = Any()
    private var used = false
    private var cancelled = false
    private var handle: Group? = null

    /** A request only: the executing thread retains ownership until cleanup is proven. */
    fun cancel() { synchronized(lock) { cancelled = true } }

    fun execute(arguments: List<String>, onLine: (String) -> Unit) {
        synchronized(lock) {
            check(!used) { "NativeDownloadProcess instances can only execute once" }
            used = true
            if (cancelled) throw CancellationException("Download canceled before startup")
        }
        val run = Run()
        val nonce = UUID.randomUUID().toString()
        val output = Tail()
        val errors = Tail()
        val readerFailure = AtomicReference<Throwable?>()
        val callbackFailure = AtomicReference<Throwable?>()
        val ready = AtomicReference<Group?>()
        var process: Process? = null
        var stdout: Thread? = null
        var stderr: Thread? = null
        var goSent = false
        var failure: Throwable? = null
        try {
            val child = start(listOf("-u", "-c", BOOTSTRAP, nonce, pythonExecutable.absolutePath) + arguments)
            process = child
            stdout = reader(child.inputStream, "download-stdout", readerFailure) { line, first ->
                if (first) {
                    val parts = line.split(' ')
                    check(parts.size == 5 && parts[0] == READY && parts[1] == nonce) { "下載程序啟動握手無效" }
                    val pid = parts[2].toLongOrNull()
                    val pgid = parts[3].toLongOrNull()
                    val sid = parts[4].toLongOrNull()
                    check(pid != null && pid > 1 && pid <= Int.MAX_VALUE && pid == pgid && pid == sid) {
                        "下載程序未建立獨立的程序群組"
                    }
                    ready.set(Group(pid))
                } else if (!line.startsWith("$READY ")) {
                    output.append(line)
                    if (readerFailure.get() == null) {
                        try { onLine(line) } catch (error: Throwable) { callbackFailure.compareAndSet(null, error); throw error }
                    }
                }
            }
            stderr = reader(child.errorStream, "download-stderr", readerFailure) { line, _ ->
                errors.append(line)
                if (readerFailure.get() == null && !line.startsWith("$READY ")) {
                    try { onLine(line) } catch (error: Throwable) { callbackFailure.compareAndSet(null, error); throw error }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMillis)
            while (true) {
                absorbInterrupt(run)
                readerFailure.get()?.let { throw it }
                if (isCancelled()) throw CancellationException("Download canceled")
                if (!goSent) {
                    val candidate = ready.get()
                    if (candidate != null) {
                        if (System.nanoTime() >= deadline) throw IOException("下載程序啟動逾時")
                        check(helper("validate", candidate, run) == "VALID" && alive(child)) {
                            "無法驗證下載程序的獨立程序群組"
                        }
                        if (System.nanoTime() >= deadline) throw IOException("下載程序啟動逾時")
                        synchronized(lock) {
                            handle = candidate
                            if (!cancelled) {
                                // Once any GO write is attempted, cleanup must use the group even if a pipe write fails.
                                goSent = true
                                child.outputStream.write("GO $nonce\n".toByteArray(Charsets.UTF_8))
                                child.outputStream.flush()
                            }
                        }
                    } else if (!alive(child)) {
                        throw IOException("下載程序未完成啟動握手${errorSuffix(errors, output)}")
                    }
                    if (!goSent && System.nanoTime() >= deadline) throw IOException("下載程序啟動逾時")
                } else if (!alive(child)) {
                    when (helper("probe", requireNotNull(handle), run)) {
                        "GONE" -> break
                        "LIVE" -> throw IOException("下載主程序已結束，但子程序仍在執行${errorSuffix(errors, output)}")
                        else -> throw IOException("無法確認下載子程序是否已結束${errorSuffix(errors, output)}")
                    }
                }
                tick(25, run)
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            val child = process
            if (child != null) {
                if (goSent) {
                    // Even an exited leader does not release ownership of live descendants.
                    cleanupGroup(requireNotNull(handle), run)
                } else {
                    // No GO means only our fixed bootstrap can exist: it never forks children.
                    stopBootstrap(child, run)
                }
                awaitExit(child, run)
                join(stdout, run)
                join(stderr, run)
                child.outputStream.closeQuietly()
            }
            synchronized(lock) { handle = null }
            if (run.interrupted) Thread.currentThread().interrupt()
        }
        readerFailure.get()?.let { if (failure == null || failure is CancellationException && !isCancelled()) failure = it }
        callbackFailure.get()?.let { failure = it }
        failure?.let { throw it }
        if (isCancelled()) throw CancellationException("Download canceled")
        val exit = requireNotNull(process).exitValue()
        if (exit != 0) throw IOException("下載程序失敗（exit $exit）${errorSuffix(errors, output)}")
    }

    private fun cleanupGroup(group: Group, run: Run) {
        var status = helper("probe", group, run)
        if (status == "GONE") return
        helper("term", group, run)
        val grace = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(termGraceMillis)
        var lastKill = 0L
        while (true) {
            status = helper("probe", group, run)
            if (status == "GONE") return
            val now = System.nanoTime()
            if (now >= grace && now - lastKill >= TimeUnit.SECONDS.toNanos(1)) {
                helper("kill", group, run)
                lastKill = now
            }
            // Unknown is never treated as success; ownership remains here until proven safe.
            tick(if (status == "LIVE") 50 else 250, run)
        }
    }

    private fun stopBootstrap(process: Process, run: Run) {
        if (!alive(process)) return
        process.destroy()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(termGraceMillis)
        while (alive(process) && System.nanoTime() < deadline) tick(10, run)
        if (alive(process)) process.destroyForcibly()
    }

    /** A separate fixed Python helper; it neither enters the SDK nor takes the archive lock. */
    private fun helper(mode: String, group: Group, run: Run): String {
        val child = try { start(listOf("-u", "-c", HELPER, mode, group.id.toString()), mergeError = true) }
            catch (_: Exception) { return "UNKNOWN" }
        val text = Tail()
        val failure = AtomicReference<Throwable?>()
        val reader = reader(child.inputStream, "download-group-probe", failure) { line, _ -> text.append(line) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (alive(child) && System.nanoTime() < deadline) tick(10, run)
        if (alive(child)) child.destroyForcibly()
        awaitExit(child, run)
        join(reader, run)
        child.outputStream.closeQuietly()
        return if (child.exitValue() == 0 && failure.get() == null) text.value().trim() else "UNKNOWN"
    }

    private fun start(arguments: List<String>, mergeError: Boolean = false): Process =
        ProcessBuilder(listOf(pythonExecutable.absolutePath) + arguments).apply {
            environment().putAll(environment)
            redirectErrorStream(mergeError)
        }.start()

    /** Reads continuously, including after callback failure; line fragments have a fixed bound. */
    private fun reader(
        stream: InputStream,
        name: String,
        failure: AtomicReference<Throwable?>,
        consume: (String, Boolean) -> Unit,
    ): Thread = Thread({
        var first = true
        val line = StringBuilder()
        fun emit() {
            val value = line.toString().removeSuffix("\r")
            line.setLength(0)
            try { consume(value, first) } catch (error: Throwable) { failure.compareAndSet(null, error) }
            first = false
        }
        try {
            stream.reader(Charsets.UTF_8).use { input ->
                val buffer = CharArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val char = buffer[index]
                        if (char == '\n') emit() else {
                            line.append(char)
                            if (line.length >= MAX_TEXT) emit()
                        }
                    }
                }
                if (line.isNotEmpty()) emit()
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        }
    }, name).apply { isDaemon = true; start() }

    private fun awaitExit(process: Process, run: Run) {
        while (alive(process)) {
            try { process.waitFor() } catch (_: InterruptedException) { run.interrupted = true; cancel() }
        }
    }

    private fun join(thread: Thread?, run: Run) {
        while (thread?.isAlive == true) {
            try { thread.join() } catch (_: InterruptedException) { run.interrupted = true; cancel() }
        }
    }

    private fun tick(millis: Long, run: Run) {
        try { Thread.sleep(millis) } catch (_: InterruptedException) { run.interrupted = true; cancel() }
    }

    private fun absorbInterrupt(run: Run) {
        if (Thread.interrupted()) { run.interrupted = true; cancel() }
    }

    private fun isCancelled(): Boolean = synchronized(lock) { cancelled }
    private fun alive(process: Process): Boolean = try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    private fun java.io.OutputStream.closeQuietly() { try { close() } catch (_: IOException) { } }
    private fun errorSuffix(stderr: Tail, stdout: Tail): String = (stderr.value().ifBlank { stdout.value() })
        .takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()

    private data class Group(val id: Long)
    private class Run(var interrupted: Boolean = false)
    private class Tail {
        private val text = StringBuilder()
        @Synchronized fun append(line: String) {
            text.append(line).append('\n')
            if (text.length > MAX_TEXT) text.delete(0, text.length - MAX_TEXT)
        }
        @Synchronized fun value(): String = text.toString()
    }

    private companion object {
        const val MAX_TEXT = 64 * 1024
        const val READY = "__MEDIA_DLER_NATIVE_READY__"
        val BOOTSTRAP = """
            import errno, os, sys
            nonce, executable = sys.argv[1:3]
            try:
                os.setsid()
            except OSError as error:
                if error.errno != errno.EPERM or not (os.getpid() == os.getpgrp() == os.getsid(0)):
                    raise
            print("__MEDIA_DLER_NATIVE_READY__ %s %d %d %d" % (nonce, os.getpid(), os.getpgrp(), os.getsid(0)), flush=True)
            if sys.stdin.readline() != "GO " + nonce + "\n":
                sys.exit(125)
            os.execv(executable, [executable] + sys.argv[3:])
        """.trimIndent()
        val HELPER = """
            import errno, os, signal, sys
            mode, target = sys.argv[1], int(sys.argv[2])
            if target <= 1 or target == os.getpgrp():
                print("UNKNOWN")
                sys.exit(1)
            if mode == "validate":
                try:
                    valid = os.getpgid(target) == target and os.getsid(target) == target
                    print("VALID" if valid else "UNKNOWN")
                except OSError:
                    print("UNKNOWN")
                sys.exit(0)
            group_missing = False
            try:
                os.killpg(target, 0)
            except OSError as error:
                if error.errno != errno.ESRCH:
                    print("UNKNOWN")
                    sys.exit(0)
                group_missing = True
            if mode in ("term", "kill"):
                if group_missing:
                    print("GONE")
                    sys.exit(0)
                try:
                    os.killpg(target, signal.SIGTERM if mode == "term" else signal.SIGKILL)
                    print("SIGNALED")
                except OSError as error:
                    print("GONE" if error.errno == errno.ESRCH else "UNKNOWN")
                sys.exit(0)
            if sys.platform.startswith("linux"):
                live, matching, unknown = False, 0, False
                try:
                    entries = os.listdir("/proc")
                except OSError:
                    print("UNKNOWN")
                    sys.exit(0)
                for entry in entries:
                    if not entry.isdigit():
                        continue
                    path = "/proc/" + entry
                    try:
                        if os.stat(path).st_uid != os.getuid():
                            continue
                        with open(path + "/stat") as source:
                            fields = source.read().rsplit(") ", 1)[1].split()
                        group, session = int(fields[2]), int(fields[3])
                        if group == target:
                            matching += 1
                            if session != target:
                                unknown = True
                            if fields[0] not in ("Z", "X"):
                                live = True
                        elif session == target and fields[0] not in ("Z", "X"):
                            unknown = True
                    except (FileNotFoundError, ProcessLookupError):
                        pass
                    except (OSError, ValueError, IndexError):
                        unknown = True
                if live:
                    print("LIVE")
                elif unknown:
                    print("UNKNOWN")
                elif matching or group_missing:
                    print("GONE")
                else:
                    try:
                        os.killpg(target, 0)
                        print("UNKNOWN")
                    except OSError as error:
                        print("GONE" if error.errno == errno.ESRCH else "UNKNOWN")
            elif sys.platform == "darwin":
                import subprocess
                try:
                    result = subprocess.run(["/bin/ps", "-axo", "pid,pgid,stat"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=2, text=True)
                    if result.returncode != 0:
                        raise OSError("ps failed")
                    members, escaped = [], False
                    for line in result.stdout.splitlines()[1:]:
                        fields = line.split()
                        if len(fields) != 3:
                            raise ValueError("unrecognized ps output")
                        if int(fields[1]) == target:
                            members.append(fields[2])
                        elif not fields[2].startswith(("Z", "X")):
                            try:
                                if os.getsid(int(fields[0])) == target:
                                    escaped = True
                            except (ProcessLookupError, PermissionError):
                                pass
                    if any(not state.startswith(("Z", "X")) for state in members):
                        print("LIVE")
                    elif escaped:
                        print("UNKNOWN")
                    elif members or group_missing:
                        print("GONE")
                    else:
                        try:
                            os.killpg(target, 0)
                            print("UNKNOWN")
                        except OSError as error:
                            print("GONE" if error.errno == errno.ESRCH else "UNKNOWN")
                except (OSError, ValueError, subprocess.TimeoutExpired):
                    print("UNKNOWN")
            else:
                print("UNKNOWN")
        """.trimIndent()
    }
}
