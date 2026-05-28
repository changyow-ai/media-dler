package com.changyow.mediadler.download

import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.isTerminal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory queue + live task state shared between the UI and the download service. */
class DownloadQueue {

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val specs = ConcurrentHashMap<String, DownloadRequest>()
    private val claimLock = Any()

    fun submit(request: DownloadRequest): String {
        val id = UUID.randomUUID().toString()
        specs[id] = request
        val item = request.item
        val task = DownloadTask(
            id = id,
            title = item.title,
            sourceUrl = item.sourceUrl,
            thumbnailUrl = item.thumbnailUrl,
            formatLabel = labelFor(request.selection),
            status = DownloadStatus.QUEUED,
            progress = 0f,
            outputUri = null,
            mimeType = null,
            errorMessage = null,
            createdAt = System.currentTimeMillis(),
        )
        _tasks.update { it + task }
        return id
    }

    /** Atomically claims the next queued task, marking it active. */
    fun claimNext(): Pair<String, DownloadRequest>? = synchronized(claimLock) {
        val next = _tasks.value.firstOrNull { it.status == DownloadStatus.QUEUED } ?: return null
        val spec = specs[next.id] ?: return null
        updateTask(next.id) { it.copy(status = DownloadStatus.DOWNLOADING, progress = -1f) }
        next.id to spec
    }

    fun hasQueued(): Boolean = _tasks.value.any { it.status == DownloadStatus.QUEUED }

    fun activeCount(): Int = _tasks.value.count { it.status == DownloadStatus.DOWNLOADING }

    fun get(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    fun setProgress(id: String, progress: Float) =
        updateTask(id) { it.copy(status = DownloadStatus.DOWNLOADING, progress = progress) }

    fun complete(id: String, outputUri: String, mimeType: String) = updateTask(id) {
        it.copy(
            status = DownloadStatus.COMPLETED,
            progress = 1f,
            outputUri = outputUri,
            mimeType = mimeType,
            errorMessage = null,
        )
    }

    fun fail(id: String, message: String) =
        updateTask(id) { it.copy(status = DownloadStatus.FAILED, errorMessage = message) }

    fun retry(id: String): Boolean {
        if (specs[id] == null) return false
        updateTask(id) { it.copy(status = DownloadStatus.QUEUED, progress = 0f, errorMessage = null) }
        return true
    }

    fun remove(id: String) {
        specs.remove(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _tasks.value.filter { it.status.isTerminal }.forEach { specs.remove(it.id) }
        _tasks.update { list -> list.filterNot { it.status.isTerminal } }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun labelFor(selection: FormatSelection): String = when (selection) {
        FormatSelection.BestVideo -> "Best"
        is FormatSelection.CappedVideo -> "${selection.maxHeight}p"
        is FormatSelection.Audio -> selection.audioFormat.ext.uppercase()
        is FormatSelection.SpecificFormat -> selection.format.label
        FormatSelection.ImageOriginal -> "Image"
    }
}
