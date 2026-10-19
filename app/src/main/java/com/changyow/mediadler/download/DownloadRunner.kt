package com.changyow.mediadler.download

import com.changyow.mediadler.core.download.DownloadFailurePolicy
import com.changyow.mediadler.core.model.*
import com.changyow.mediadler.core.repo.DownloadOutput
import com.changyow.mediadler.core.repo.Downloader
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import java.net.UnknownHostException

/** Application-owned coordinator: a service lifecycle never owns the native attempt's lifetime. */
class DownloadRunner(
    private val queue: DownloadQueue,
    private val resolver: QueuedRequestResolver,
    private val downloader: Downloader,
    private val scope: CoroutineScope,
    private val listener: Listener = NoOp,
    private val networkAvailable: StateFlow<Boolean> = MutableStateFlow(true),
) {
    enum class Phase { IDLE, RUNNING, STOPPING, PAUSED }
    data class State(val phase: Phase, val owner: String? = null, val startId: Int = 0, val pause: QueuePause? = null)

    interface Listener {
        fun onChanged(task: DownloadTask) {}
        suspend fun onCompleted(task: DownloadTask, output: DownloadOutput) {}
        suspend fun onFailed(task: DownloadTask, error: Throwable) {}
        fun onStopped(taskId: String, task: DownloadTask?) {}
        fun onPaused(reason: QueuePause) {}
    }
    object NoOp : Listener

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pendingStops = ConcurrentHashMap.newKeySet<String>()
    private val _state = MutableStateFlow(State(
        if (queue.pauseReason() == null) Phase.IDLE else Phase.PAUSED, pause = queue.pauseReason(),
    ))
    val state: StateFlow<State> = _state.asStateFlow()

    init { scope.launch { coordinate() } }

    fun wake(owner: String, startId: Int, userInitiated: Boolean = false, resumeAll: Boolean = false) {
        commands.trySend(Command.Wake(owner, startId, userInitiated, resumeAll))
    }

    suspend fun pauseTask(id: String): Boolean {
        val reply = CompletableDeferred<Boolean>()
        commands.send(Command.PauseTask(id, reply))
        return reply.await()
    }

    suspend fun resumeTask(id: String): Boolean {
        val reply = CompletableDeferred<Boolean>()
        commands.send(Command.ResumeTask(id, reply))
        return reply.await()
    }

    suspend fun downloadNow(id: String): DownloadNowResult {
        val reply = CompletableDeferred<DownloadNowResult>()
        commands.send(Command.DownloadNow(id, reply))
        return reply.await()
    }

    fun stop(owner: String, reason: QueuePause = QueuePause(QueuePauseKind.SYSTEM, "下載已暫停，開啟 App 後可繼續排程")) {
        // A timeout must close the claim gate immediately, even if Finished is already queued.
        pendingStops.add(owner)
        commands.trySend(Command.Stop(owner, reason))
    }

    /** A worker can own a paused FIFO but cannot steal another active owner's attempt. */
    suspend fun acquireBackground(owner: String): Boolean {
        val reply = CompletableDeferred<Boolean>(currentCoroutineContext()[Job])
        commands.send(Command.AcquireBackground(owner, reply))
        return reply.await()
    }

    /** Run a bounded worker slice, retaining its checkpoint only after native cleanup has finished. */
    suspend fun runBackgroundWindow(owner: String, windowMillis: Long? = null) {
        var expired = false
        try {
            suspend fun runWindow() {
                if (!acquireBackground(owner)) return
                state.first { it.owner != owner || it.phase !in setOf(Phase.RUNNING, Phase.STOPPING) }
            }
            if (windowMillis == null) runWindow()
            else expired = withTimeoutOrNull(windowMillis) { runWindow(); true } == null
        } finally {
            withContext(NonCancellable) {
                stopAndAwait(owner, DownloadFailurePolicy.networkPause, windowExpired = expired)
            }
        }
    }

    /** Returns only after the owner's native attempt has finished its cancellation cleanup. */
    suspend fun stopAndAwait(owner: String, reason: QueuePause = QueuePause(QueuePauseKind.SYSTEM, "下載已暫停，開啟 App 後可繼續排程")) {
        stopAndAwait(owner, reason, windowExpired = false)
    }

    private suspend fun stopAndAwait(owner: String, reason: QueuePause, windowExpired: Boolean) {
        val reply = CompletableDeferred<Unit>()
        pendingStops.add(owner)
        commands.send(Command.Stop(owner, reason, reply, windowExpired))
        reply.await()
    }

    private suspend fun coordinate() {
        var owner: String? = null
        var startId = 0
        var preferResolved = false
        var running: Attempt? = null
        var pendingAck: DownloadClaim? = null
        var pause = queue.pauseReason()
        var persistenceBlocked = false

        fun publish() {
            _state.value = State(
                when {
                    running?.stopping == true -> Phase.STOPPING
                    running != null -> Phase.RUNNING
                    pause != null || queue.pauseReason() != null -> Phase.PAUSED
                    else -> Phase.IDLE
                }, owner, startId, pause ?: queue.pauseReason(),
            )
        }

        fun park(reason: QueuePause, taskId: String? = null) {
            pause = reason
            try { pause = persisted { queue.pause(reason, taskId) } ?: reason }
            catch (_: PersistenceFailure) { persistenceBlocked = true }
            pause?.let { effective -> notify { listener.onPaused(effective) } }
        }

        fun pump() {
            val currentOwner = owner ?: return
            if (currentOwner in pendingStops || running != null || pendingAck != null || pause != null || queue.pauseReason() != null) return
            val claim = try { persisted { queue.claimNext(preferResolved) } } catch (error: PersistenceFailure) {
                persistenceBlocked = true
                park(persistencePause(error))
                return
            } ?: return
            val attempt = Attempt(claim)
            running = attempt
            attempt.job = scope.launch(start = CoroutineStart.LAZY) { execute(attempt) }
            attempt.job.invokeOnCompletion { commands.trySend(Command.Finished(attempt)) }
            attempt.job.start()
        }

        fun <T> changeItem(id: String, reply: CompletableDeferred<T>, rejected: T, change: () -> T) {
            try {
                val result = persisted(change)
                running?.takeIf { queue.get(it.claim.id)?.interruption != null }?.let {
                    it.stopping = true
                    it.interrupted = true
                    it.job.cancel(CancellationException("Download interrupted by user"))
                }
                changed(id)
                // Starting idle work still requires a live foreground/background owner wake.
                // An interrupted owner's Finished command pumps only after native cleanup.
                reply.complete(result)
            } catch (error: PersistenceFailure) {
                persistenceBlocked = true
                park(persistencePause(error), id)
                running?.let { it.stopping = true; it.interrupted = true; it.job.cancel() }
                reply.complete(rejected)
            }
        }

        for (command in commands) {
            when (command) {
                is Command.PauseTask -> changeItem(command.id, command.reply, false) { queue.pauseTask(command.id) }
                is Command.ResumeTask -> changeItem(command.id, command.reply, false) { queue.resumeTask(command.id) }
                is Command.DownloadNow -> changeItem(command.id, command.reply, DownloadNowResult.REJECTED) {
                    queue.downloadNow(command.id)
                }
                is Command.Wake -> {
                    startId = if (owner == command.owner) maxOf(startId, command.startId) else command.startId
                    owner = command.owner
                    preferResolved = false
                    // Explicit retry/removal may have cleared the durable pause. Failed writes
                    // retain their separate safety gate; an obsolete local anchor cannot trap new work.
                    if (!persistenceBlocked && queue.pauseReason() == null) pause = null
                    try {
                        persisted { queue.resumeNetwork() }
                        if (!persistenceBlocked && pause?.kind == QueuePauseKind.NETWORK) pause = queue.pauseReason()
                    } catch (error: PersistenceFailure) {
                        persistenceBlocked = true
                        park(persistencePause(error))
                    }
                    if (command.userInitiated || command.resumeAll) {
                        try {
                            pendingAck?.let { claim ->
                                persisted { queue.acknowledgeStop(claim, interrupted = true) }
                                notify { listener.onStopped(claim.id, queue.get(claim.id)) }
                                pendingAck = null
                            }
                            persisted { queue.resume(systemOnly = !command.resumeAll) }
                            persistenceBlocked = false
                            pause = queue.pauseReason()
                        } catch (error: PersistenceFailure) {
                            persistenceBlocked = true
                            park(persistencePause(error), pendingAck?.id)
                            running?.let { it.stopping = true; it.interrupted = true; it.job.cancel() }
                        }
                    }
                    pump()
                }
                is Command.Stop -> {
                    pendingStops.remove(command.owner)
                    if (command.owner != owner) { command.reply?.complete(Unit); continue }
                    // A lifecycle stop must not turn an explicit storage pause into auto-resume.
                    if (running != null || pendingAck != null || queue.hasQueued()) {
                        val activeTask = running?.claim?.id?.let(queue::get)?.takeIf { it.status.isActive }
                        val reason = when {
                            command.windowExpired && activeTask?.status == DownloadStatus.PROCESSING ->
                                QueuePause(QueuePauseKind.SYSTEM, "背景處理已暫停，開啟 App 後可繼續排程")
                            activeTask != null && (command.windowExpired && running?.canResume != true ||
                                command.reason.kind == QueuePauseKind.NETWORK && running?.canResume == false) ->
                                QueuePause(QueuePauseKind.SYSTEM, "這項下載無法在背景分次接續，請開啟 App 後繼續排程")
                            else -> command.reason
                        }
                        park(reason, running?.claim?.id)
                    } else {
                        // A worker releasing a drained FIFO must not emit a false pause notification.
                        pause = queue.pauseReason()
                    }
                    running?.let {
                        command.reply?.let(it.stopReplies::add)
                        it.stopping = true; it.interrupted = true; it.job.cancel()
                    } ?: command.reply?.complete(Unit)
                }
                is Command.AcquireBackground -> {
                    val refused = !command.reply.isActive || !networkAvailable.value || persistenceBlocked || pendingAck != null ||
                        pendingStops.isNotEmpty() || running?.stopping == true ||
                        running != null && owner != command.owner ||
                        queue.tasks.value.any { it.queuePause?.kind in setOf(QueuePauseKind.SYSTEM, QueuePauseKind.STORAGE) }
                    if (refused) { command.reply.complete(false); continue }
                    try {
                        persisted { queue.resumeNetwork() }
                        pause = queue.pauseReason()
                        owner = command.owner
                        startId = 0
                        preferResolved = true
                        pump()
                        publish()
                        command.reply.complete(!persistenceBlocked)
                    } catch (error: PersistenceFailure) {
                        persistenceBlocked = true
                        park(persistencePause(error))
                        command.reply.complete(false)
                    }
                }
                is Command.Finished -> {
                    val attempt = command.attempt
                    if (running !== attempt) continue
                    running = null
                    // A lazy coroutine canceled before its first instruction never enters finally.
                    if (!attempt.entered) {
                        try {
                            persisted { queue.acknowledgeStop(attempt.claim, interrupted = true) }
                            notify { listener.onStopped(attempt.claim.id, queue.get(attempt.claim.id)) }
                        } catch (error: PersistenceFailure) {
                            attempt.ackPending = true
                            attempt.persistenceFailed = true
                            attempt.pause = persistencePause(error)
                        }
                    }
                    if (attempt.ackPending) pendingAck = attempt.claim
                    if (attempt.persistenceFailed) persistenceBlocked = true
                    if (attempt.persistenceFailed) {
                        attempt.pause?.let { park(it, attempt.claim.id) }
                    } else if (attempt.pause != null && !persistenceBlocked) {
                        // pause()/fail() already persisted it. A quick retry may have cleared it
                        // while onFailed was suspending; never re-apply that obsolete pause.
                        val pausedTask = queue.tasks.value.firstOrNull { it.queuePause != null }
                        pause = pausedTask?.queuePause
                        pause?.let { reason -> notify { listener.onPaused(reason) } }
                    }
                    attempt.stopReplies.forEach { it.complete(Unit) }
                    pump()
                }
            }
            publish()
        }
    }

    private suspend fun execute(attempt: Attempt) {
        attempt.entered = true
        val claim = attempt.claim
        var stopped = false
        try {
            coroutineScope {
                val attemptContext = currentCoroutineContext()
                val cancellation = launch {
                    queue.tasks.first { tasks -> tasks.any { it.id == claim.id &&
                        (it.cancelRequested || it.interruption != null) } }
                    attemptContext.cancel(CancellationException("Download interrupted by user"))
                }
                try {
                    changed(claim.id)
                    val output = download(attempt)
                    currentCoroutineContext().ensureActive()
                    if (persisted { queue.complete(claim, output.uri, output.mimeType) }) {
                        queue.get(claim.id)?.let { task -> notifySuspending { listener.onCompleted(task, output) } }
                    }
                } finally {
                    cancellation.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            stopped = true
            throw cancelled
        } catch (error: PersistenceFailure) {
            attempt.persistenceFailed = true
            attempt.pause = persistencePause(error)
        } catch (error: Exception) {
            try {
                val connectionFailure = DownloadFailurePolicy.isTransientNetworkFailure(error)
                if (connectionFailure && networkAvailable.value) {
                    // A socket can fail just before ConnectivityManager reports a lost network.
                    // Observe one bounded callback window, then use the current state.
                    withTimeoutOrNull(500) { networkAvailable.first { !it } }
                }
                currentCoroutineContext().ensureActive()
                checkItemInterruption(claim)
                if (connectionFailure && !networkAvailable.value) {
                    if (persisted { queue.parkNetwork(claim) }) {
                        attempt.pause = DownloadFailurePolicy.networkPause
                        changed(claim.id)
                    }
                } else {
                    DownloadFailurePolicy.pauseFor(error)?.let {
                        attempt.pause = it
                        persisted { queue.pause(it, claim.id) }
                    }
                    persisted { queue.fail(claim, DownloadFailurePolicy.sourceFailureMessage(error)) }
                    queue.get(claim.id)?.takeIf { it.status == DownloadStatus.FAILED }
                        ?.let { task -> notifySuspending { listener.onFailed(task, error) } }
                }
            } catch (failure: PersistenceFailure) {
                attempt.persistenceFailed = true
                attempt.pause = persistencePause(failure)
            }
        } finally {
            withContext(NonCancellable) {
                stopped = stopped || attempt.interrupted || queue.get(claim.id)?.cancelRequested == true
                try {
                    persisted { queue.acknowledgeStop(claim, interrupted = attempt.interrupted || attempt.pause != null) }
                    if (stopped) notify { listener.onStopped(claim.id, queue.get(claim.id)) }
                } catch (error: PersistenceFailure) {
                    attempt.persistenceFailed = true
                    attempt.ackPending = true
                    attempt.pause = persistencePause(error)
                }
            }
        }
    }

    private suspend fun download(attempt: Attempt): DownloadOutput {
        val claim = attempt.claim
        // The publication journal and completed private media need no network.
        ensureAttemptActive(claim, requiresNetwork = false)
        downloader.recover(claim.id) { progress ->
            persisted { queue.setProgress(claim, progress) }
            checkItemInterruption(claim)
            changed(claim.id)
        }.getOrThrow()?.let { return it }
        repeat(2) { refresh ->
            // A refreshed URL must establish its own checkpoint support.
            attempt.canResume = null
            ensureAttemptActive(claim)
            if (claim.spec is QueuedSpec.Page) {
                persisted { queue.extracting(claim, if (refresh == 0) null else "串流連結已失效，正在重新解析同一集") }
                changed(claim.id)
            }
            val request = claim.resolvedRequest?.takeIf { refresh == 0 } ?: resolver.resolve(claim.spec)
            ensureAttemptActive(claim)
            persisted { queue.resolved(claim, request) }
            changed(claim.id)
            try {
                return downloader.download(request, claim.id) { progress ->
                    progress.canResume?.let { attempt.canResume = it }
                    persisted { queue.setProgress(claim, progress) }
                    // A pause committed before PROCESSING must stop the pipeline before publish.
                    checkItemInterruption(claim)
                    changed(claim.id)
                }.getOrThrow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is PersistenceFailure || refresh != 0 || claim.spec !is QueuedSpec.Page || !DownloadFailurePolicy.shouldRefresh(error)) throw error
            }
        }
        error("影片連結重新解析失敗")
    }

    private fun changed(id: String) { queue.get(id)?.let { task -> notify { listener.onChanged(task) } } }

    private suspend fun ensureAttemptActive(claim: DownloadClaim, requiresNetwork: Boolean = true) {
        currentCoroutineContext().ensureActive()
        checkItemInterruption(claim)
        if (requiresNetwork && !networkAvailable.value) throw UnknownHostException("Network disconnected")
    }

    private fun checkItemInterruption(claim: DownloadClaim) {
        val task = queue.get(claim.id)
        if (task == null || task.cancelRequested || task.interruption != null) {
            throw CancellationException("Download interrupted by user")
        }
    }

    private inline fun notify(block: () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Notification/metadata failures do not change download state. */ }
    }

    private suspend fun notifySuspending(block: suspend () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Output has already committed. */ }
    }

    private inline fun <T> persisted(block: () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw PersistenceFailure(error) }

    private fun persistencePause(error: PersistenceFailure): QueuePause = DownloadFailurePolicy.pauseFor(error)
        ?: QueuePause(QueuePauseKind.SYSTEM, "排程資料暫時無法儲存，請重新開啟 App 後繼續")

    private class PersistenceFailure(cause: Throwable) : Exception(cause)
    private class Attempt(val claim: DownloadClaim) {
        lateinit var job: Job
        var stopping = false
        @Volatile var entered = false
        @Volatile var interrupted = false
        @Volatile var canResume: Boolean? = null
        var pause: QueuePause? = null
        var ackPending = false
        var persistenceFailed = false
        val stopReplies = mutableListOf<CompletableDeferred<Unit>>()
    }
    private sealed interface Command {
        data class PauseTask(val id: String, val reply: CompletableDeferred<Boolean>) : Command
        data class ResumeTask(val id: String, val reply: CompletableDeferred<Boolean>) : Command
        data class DownloadNow(val id: String, val reply: CompletableDeferred<DownloadNowResult>) : Command
        data class Wake(val owner: String, val startId: Int, val userInitiated: Boolean, val resumeAll: Boolean) : Command
        data class Stop(val owner: String, val reason: QueuePause, val reply: CompletableDeferred<Unit>? = null,
            val windowExpired: Boolean = false) : Command
        data class AcquireBackground(val owner: String, val reply: CompletableDeferred<Boolean>) : Command
        data class Finished(val attempt: Attempt) : Command
    }
}
