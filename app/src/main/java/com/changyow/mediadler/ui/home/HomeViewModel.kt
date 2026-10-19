package com.changyow.mediadler.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.EpisodeMetadata
import com.changyow.mediadler.core.model.ItemInterruption
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
        require(task.sourceKind != SourceKind.GENERAL && !task.status.isActive && task.interruption == null) { "下載進行中時無法修改資料" }
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

internal enum class HomeTaskCommand { PAUSE, RESUME, DOWNLOAD_NOW }

internal fun taskControlsAvailable(task: DownloadTask): Boolean =
    !task.cancelRequested && task.interruption == null

internal fun canPauseTask(task: DownloadTask): Boolean = taskControlsAvailable(task) && task.status in
    setOf(DownloadStatus.QUEUED, DownloadStatus.EXTRACTING, DownloadStatus.DOWNLOADING)

internal fun canResumeTask(task: DownloadTask): Boolean =
    taskControlsAvailable(task) && task.status == DownloadStatus.PAUSED

internal fun canDownloadTaskNow(task: DownloadTask): Boolean = taskControlsAvailable(task) &&
    task.status in setOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED)

internal fun scheduledHomeTasks(tasks: List<DownloadTask>): List<DownloadTask> =
    tasks.filter { !it.status.isTerminal && it.status != DownloadStatus.PAUSED }

internal fun taskStatusText(task: DownloadTask, queueNumber: Int?, pending: HomeTaskCommand? = null): String = when {
    task.cancelRequested -> "正在取消…"
    task.interruption == ItemInterruption.MANUAL_PAUSE || pending == HomeTaskCommand.PAUSE -> "正在暫停…"
    task.interruption == ItemInterruption.PRIORITY_REQUEUE -> "正在切換下載…"
    pending == HomeTaskCommand.RESUME -> "正在繼續下載…"
    pending == HomeTaskCommand.DOWNLOAD_NOW -> "正在安排下載…"
    else -> when (task.status) {
        DownloadStatus.QUEUED -> queueNumber?.let { "排隊中 · 排程第 $it 項" } ?: "排隊中"
        DownloadStatus.PAUSED -> if (task.progress in 0f..1f) "已暫停 ${(task.progress * 100).toInt()}%" else "已暫停"
        DownloadStatus.EXTRACTING -> "解析中"
        DownloadStatus.DOWNLOADING -> if (task.progress in 0f..1f) "下載中 ${(task.progress * 100).toInt()}%" else "下載中"
        DownloadStatus.PROCESSING -> "處理中"
        DownloadStatus.COMPLETED -> "已完成"
        DownloadStatus.FAILED -> "下載失敗"
        DownloadStatus.CANCELED -> "已取消"
    }
}

internal fun taskProgressDetail(task: DownloadTask): String? = when {
    task.status == DownloadStatus.PAUSED && task.canResume == false -> "此來源不支援續傳，繼續時會重新下載"
    task.status == DownloadStatus.PAUSED -> null
    !task.status.isTerminal -> task.progressDetail
    else -> null
}
