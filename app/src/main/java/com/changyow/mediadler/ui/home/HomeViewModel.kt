package com.changyow.mediadler.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.EpisodeMetadata
import com.changyow.mediadler.core.model.SourceKind
import com.changyow.mediadler.core.model.isActive
import com.changyow.mediadler.core.model.isTerminal
import com.changyow.mediadler.data.history.HistoryStore
import com.changyow.mediadler.download.DownloadQueue
import com.changyow.mediadler.download.PreviewStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

class HomeViewModel(
    private val queue: DownloadQueue,
    private val history: HistoryStore,
    private val previewStore: PreviewStore,
) : ViewModel() {

    /** Live in-memory tasks merged with persisted history (live wins on id collision). */
    val tasks: StateFlow<List<DownloadTask>> =
        combine(queue.tasks, history.history) { live, persisted ->
            mergeHomeTasks(live, persisted)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry(id: String): Boolean = queue.retry(id)
    fun retryAllFailed(): Int = queue.retryAllFailed()

    // History-only records lack a recoverable request and cannot be retried by the queue.
    val retryableFailedCount: StateFlow<Int> = queue.tasks.map { live ->
        live.count { it.status == DownloadStatus.FAILED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun cancel(id: String) = queue.cancel(id)

    suspend fun editMetadata(task: DownloadTask, metadata: EpisodeMetadata) = withContext(Dispatchers.IO) {
        require(task.sourceKind != SourceKind.GENERAL && !task.status.isActive) { "下載進行中時無法修改資料" }
        if (queue.get(task.id) != null) {
            check(queue.editMetadata(task.id, metadata)) { "項目已開始下載或已移除，請重新開啟選單" }
            queue.get(task.id)?.takeIf { it.status.isTerminal }?.let { history.add(it) }
        } else {
            history.add(metadata.applyTo(task))
        }
    }

    fun remove(id: String) {
        tasks.value.firstOrNull { it.id == id }?.let { previewStore.delete(it.previewPath) }
        queue.remove(id)
        viewModelScope.launch { history.remove(id) }
    }

    fun clearFinished() {
        tasks.value.filter { it.status.isTerminal }.forEach { previewStore.delete(it.previewPath) }
        queue.clearFinished()
        viewModelScope.launch { history.clear() }
    }
}

internal data class TranscriptSource(val uri: String, val isUrl: Boolean)

internal fun mergeHomeTasks(live: List<DownloadTask>, persisted: List<DownloadTask>): List<DownloadTask> =
    (live + persisted.filter { p -> live.none { it.id == p.id } })
        .sortedWith(compareBy<DownloadTask> { it.status.isTerminal }
            .thenBy { if (it.status.isTerminal) 0L else it.queuePosition }
            .thenByDescending { if (it.status.isTerminal) it.createdAt else 0L })

/** Existing completed media is preferred over downloading audio from the page again. */
internal fun transcriptSourceFor(task: DownloadTask): TranscriptSource? {
    if (task.formatLabel == "Image" || task.mimeType?.startsWith("image/") == true) return null
    val output = task.outputUri
    if (task.status == DownloadStatus.COMPLETED && output != null &&
        (task.mimeType?.startsWith("video/") == true || task.mimeType?.startsWith("audio/") == true)) {
        return TranscriptSource(output, false)
    }
    val page = runCatching { URI(task.pageUrl) }.getOrNull()
        ?.takeIf { it.scheme in setOf("https", "http") && it.host != null && it.rawUserInfo == null }
        ?: return null
    return TranscriptSource(page.toString(), true)
}
