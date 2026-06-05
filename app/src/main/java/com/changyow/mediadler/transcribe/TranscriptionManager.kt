package com.changyow.mediadler.transcribe

import android.content.Context
import com.changyow.mediadler.data.transcribe.TranscriptStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Collections

/**
 * Single source of truth for transcription jobs. Holds the live in-memory state the UI observes,
 * persists checkpoints/terminal states via [TranscriptStore] (so jobs resume and survive process
 * death), runs the claim queue the foreground service drains, and owns the cancel + temp-cleanup
 * ("放棄") logic. High-frequency live updates (progress/partial text) stay in memory; only
 * checkpoints and terminal transitions are persisted.
 */
class TranscriptionManager(
    private val context: Context,
    private val store: TranscriptStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _jobs = MutableStateFlow<List<TranscriptJob>>(emptyList())
    val jobs: StateFlow<List<TranscriptJob>> = _jobs.asStateFlow()

    private val queue = ArrayDeque<String>()
    private val cancelRequested = Collections.synchronizedSet(HashSet<String>())

    // Serialises persistence so a late upsert can't interleave with a remove and resurrect a job.
    private val persistMutex = Mutex()

    // Flips true once persisted jobs are loaded; launch-time resume/auto-open must await this so they
    // don't read empty state before hydrate() (which reads DataStore off the main thread) has run.
    private val _hydrated = MutableStateFlow(false)
    val hydrated: StateFlow<Boolean> = _hydrated.asStateFlow()

    init {
        scope.launch { hydrate() }
    }

    /** Suspends until the persisted jobs/queue have been loaded. */
    suspend fun awaitHydrated() {
        hydrated.first { it }
    }

    /** Loads persisted jobs, requeuing any left RUNNING by a previous process (resume on launch). */
    private suspend fun hydrate() {
        val loaded = store.load().map {
            if (it.status == TranscriptStatus.RUNNING) it.copy(status = TranscriptStatus.QUEUED) else it
        }
        _jobs.update { current ->
            val ids = current.mapTo(HashSet()) { it.id }
            (current + loaded.filter { it.id !in ids }).sortedByDescending { it.createdAt }
        }
        synchronized(queue) {
            loaded.filter { it.status == TranscriptStatus.QUEUED && it.id !in queue }
                .forEach { queue.add(it.id) }
        }
        // Sweep input copies orphaned by a crash/old build: keep only those a resumable local-file
        // job still needs (URL jobs re-download; completed/cancelled jobs don't resume).
        val keep = loaded.filter {
            !it.isUrl && (it.status == TranscriptStatus.QUEUED || it.status == TranscriptStatus.FAILED)
        }.mapTo(HashSet()) { it.id }
        pruneOrphanInputs(keep)
        _hydrated.value = true
    }

    fun job(id: String): TranscriptJob? = _jobs.value.firstOrNull { it.id == id }

    /**
     * Creates or resumes a job. [id] is stable across re-shares (derived from the original source);
     * [sourceUri] is what the engine reads (a private file copy for local shares). Returns the id.
     */
    fun enqueue(id: String, sourceUri: String, isUrl: Boolean, label: String, now: Long): String {
        val existing = job(id)
        val job = when {
            existing == null -> TranscriptJob(
                id = id, sourceUri = sourceUri, isUrl = isUrl, label = label,
                status = TranscriptStatus.QUEUED, createdAt = now,
            )
            existing.status == TranscriptStatus.COMPLETED -> return id // show existing result
            existing.status == TranscriptStatus.RUNNING ||
                existing.status == TranscriptStatus.QUEUED -> return id // already in flight
            // FAILED resumes from its checkpoint; CANCELLED restarts clean.
            existing.status == TranscriptStatus.CANCELLED ->
                existing.copy(status = TranscriptStatus.QUEUED, progress = 0f, text = "",
                    completedWindows = 0, error = null, seen = false, createdAt = now)
            else -> existing.copy(status = TranscriptStatus.QUEUED, error = null, seen = false)
        }
        cancelRequested.remove(id)
        put(job)
        persistUpsert(job)
        synchronized(queue) { if (id !in queue) queue.add(id) }
        return id
    }

    /** Claims the next pending job, marking it RUNNING. Returns null when the queue is empty. */
    fun claimNext(): TranscriptJob? {
        val id = synchronized(queue) { queue.removeFirstOrNull() } ?: return null
        val job = job(id) ?: return claimNext()
        if (job.isTerminal && job.status != TranscriptStatus.FAILED) return claimNext()
        val running = job.copy(status = TranscriptStatus.RUNNING, error = null)
        put(running)
        persistUpsert(running)
        return running
    }

    fun hasPending(): Boolean = synchronized(queue) { queue.isNotEmpty() }

    /**
     * Binds the job to the engine about to run it, returning the job to resume from. If the engine
     * differs from the one that wrote the checkpoint (different window scheme), the checkpoint is
     * discarded so the job re-transcribes cleanly instead of stitching incompatible windows.
     */
    fun beginRun(id: String, engineId: String): TranscriptJob? {
        val job = mutate(id) {
            if (it.engineId != null && it.engineId != engineId) {
                it.copy(engineId = engineId, completedWindows = 0, totalWindows = 0, text = "", language = null)
            } else {
                it.copy(engineId = engineId)
            }
        } ?: return null
        persistUpsert(job)
        return job
    }

    // --- live, in-memory only (high frequency) ---

    fun setProgress(id: String, progress: Float) =
        mutate(id) { it.copy(progress = progress.coerceIn(0f, 1f)) }

    fun setLiveText(id: String, text: String) = mutate(id) { it.copy(text = text) }

    /** Records the human-readable transcription method (engine + model) for display; persisted. */
    fun setMethod(id: String, method: String) {
        val job = mutate(id) { it.copy(method = method) } ?: return
        persistUpsert(job)
    }

    /** Repoints a job at a private-storage copy (so it survives the share grant / process death). */
    fun updateSource(id: String, sourceUri: String) {
        val job = mutate(id) { it.copy(sourceUri = sourceUri) } ?: return
        persistUpsert(job)
    }

    // --- persisted ---

    fun checkpoint(id: String, completedWindows: Int, totalWindows: Int, text: String, language: String?) {
        val job = mutate(id) {
            it.copy(completedWindows = completedWindows, totalWindows = totalWindows,
                text = text, language = language)
        } ?: return
        persistUpsert(job)
    }

    fun complete(id: String, text: String, language: String?) {
        val job = mutate(id) {
            it.copy(status = TranscriptStatus.COMPLETED, progress = 1f, text = text,
                language = language, seen = false, error = null)
        } ?: return
        persistUpsert(job)
    }

    fun fail(id: String, message: String) {
        val job = mutate(id) { it.copy(status = TranscriptStatus.FAILED, error = message) } ?: return
        persistUpsert(job)
    }

    fun markSeen(id: String) {
        val job = job(id) ?: return
        if (job.seen) return
        val updated = mutate(id) { it.copy(seen = true) } ?: return
        persistUpsert(updated)
    }

    /** Requests cancellation ("放棄"): aborts the running window, drops the job, cleans its temp. */
    fun cancel(id: String) {
        cancelRequested.add(id)
        synchronized(queue) { queue.remove(id) }
        _jobs.update { list -> list.filterNot { it.id == id } }
        scope.launch {
            persistMutex.withLock { store.remove(id) }
            // Only this job's scratch; a running job's downloaded audio is freed by the service.
            deleteInputCopy(id)
        }
    }

    fun isCancelRequested(id: String): Boolean = id in cancelRequested

    fun delete(id: String) {
        synchronized(queue) { queue.remove(id) }
        cancelRequested.remove(id)
        _jobs.update { list -> list.filterNot { it.id == id } }
        scope.launch {
            persistMutex.withLock { store.remove(id) }
            deleteInputCopy(id)
        }
    }

    fun clearAll() {
        synchronized(queue) { queue.clear() }
        _jobs.value = emptyList()
        scope.launch {
            persistMutex.withLock { store.clear() }
            clearTempFiles()
        }
    }

    fun firstUnseenCompleted(): TranscriptJob? =
        _jobs.value.firstOrNull { it.status == TranscriptStatus.COMPLETED && !it.seen }

    /** Deletes all transcription scratch files (downloaded audio, fetched subtitles, file copies). */
    fun clearTempFiles() {
        runCatching { File(context.cacheDir, "transcribe").deleteRecursively() }
    }

    /** Deletes one job's private input copy (no-op if it was never copied / already gone). */
    private fun deleteInputCopy(id: String) {
        runCatching { File(File(context.cacheDir, "transcribe/input"), id).delete() }
    }

    /** Drops input copies whose id isn't in [keep] (orphans left by a crash or older build). */
    private fun pruneOrphanInputs(keep: Set<String>) {
        runCatching {
            File(context.cacheDir, "transcribe/input").listFiles()?.forEach { file ->
                if (file.name !in keep) file.delete()
            }
        }
    }

    /**
     * Persists [job], serialised with removals. A job cancelled while this write was queued is not
     * re-created: the cancelled-id guard makes a late upsert a no-op regardless of which order it and
     * the cancel's removal reach the store.
     */
    private fun persistUpsert(job: TranscriptJob) {
        scope.launch {
            persistMutex.withLock {
                if (job.id in cancelRequested) return@withLock
                store.upsert(job)
            }
        }
    }

    private fun put(job: TranscriptJob) {
        _jobs.update { list ->
            (listOf(job) + list.filterNot { it.id == job.id }).sortedByDescending { it.createdAt }
        }
    }

    /** Applies [transform] to the job and returns the new value (null if the job is gone). */
    private fun mutate(id: String, transform: (TranscriptJob) -> TranscriptJob): TranscriptJob? {
        var updated: TranscriptJob? = null
        _jobs.update { list ->
            list.map { if (it.id == id) transform(it).also { u -> updated = u } else it }
        }
        return updated
    }
}
