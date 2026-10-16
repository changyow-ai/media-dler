package com.changyow.mediadler.download

import com.changyow.mediadler.core.model.*
import com.changyow.mediadler.core.repo.DownloadQueueStore
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Durable FIFO. The database transaction is the authority; flows are its UI projection. */
class DownloadQueue(
    private val store: DownloadQueueStore,
    private val workRoot: File,
    private val beforeDelete: (String) -> Boolean = { true },
) {
    private val lock = Any()
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    init {
        // Once per process/container, never on every service start.
        val deleted = mutableListOf<String>()
        val completed = mutableListOf<String>()
        mutate { records -> records.mapNotNull { record ->
            when {
                record.deleteRequested -> {
                    deleted += record.task.id
                    record.copy(task = record.task.copy(status = DownloadStatus.CANCELED, cancelRequested = false))
                }
                record.task.status == DownloadStatus.COMPLETED -> { completed += record.task.id; record }
                record.task.cancelRequested -> record.copy(task = record.task.copy(status = DownloadStatus.CANCELED, cancelRequested = false))
                record.task.status.isActive -> record.copy(task = record.task.copy(status = DownloadStatus.QUEUED, progress = -1f, progressDetail = "等待恢復下載"), attempt = record.attempt + 1)
                else -> record
            }
        } }
        deleted.forEach(::finishDeletion)
        completed.forEach(::deleteScratch)
    }

    fun submit(request: DownloadRequest): String = submitBatch(listOf(specFor(request))).single()
    fun submitRequests(requests: List<DownloadRequest>, allowPageDuplicates: Boolean = false): List<String> =
        submitBatch(requests.map(::specFor), allowPageDuplicates)

    fun submitPages(pages: List<EpisodePage>, selection: FormatSelection): List<String> =
        submitBatch(pages.map { QueuedSpec.Page(it, selection) })

    /** All pages land in one transaction before any extraction or service wake-up. */
    fun submitBatch(specs: List<QueuedSpec>, allowPageDuplicates: Boolean = false): List<String> {
        val ids = mutableListOf<String>()
        mutate { before ->
            val records = before.toMutableList()
            var position = (records.maxOfOrNull { it.task.queuePosition } ?: 0L) + 1
            for (spec in specs) {
                if (spec is QueuedSpec.Page) {
                    require(spec.page.kind != SourceKind.GENERAL &&
                        com.changyow.mediadler.core.extract.SpecialSources.episodePageOrNull(spec.page.playbackUrl)?.kind == spec.page.kind) {
                        "這個網址不是支援的單集播放頁"
                    }
                }
                val duplicate = if (allowPageDuplicates) null else (spec as? QueuedSpec.Page)?.let { page -> records.firstOrNull {
                    val existing = it.spec as? QueuedSpec.Page
                    existing != null && !it.task.status.isTerminal && pageKey(existing.page) == pageKey(page.page)
                } }
                if (duplicate != null) { ids += duplicate.task.id; continue }
                val id = UUID.randomUUID().toString()
                records += QueuedDownload(taskFor(id, position++, spec), spec)
                ids += id
            }
            records
        }
        return ids
    }

    fun claimNext(): DownloadClaim? {
        var claim: DownloadClaim? = null
        mutate { records ->
            if (records.any { it.task.status.isActive || it.task.queuePause != null }) return@mutate records
            val next = records.filter { it.task.status == DownloadStatus.QUEUED }.minByOrNull { it.task.queuePosition }
                ?: return@mutate records
            val attempt = next.attempt + 1
            claim = DownloadClaim(next.task.id, attempt, next.spec)
            records.map { if (it.task.id != next.task.id) it else it.copy(attempt = attempt,
                task = it.task.copy(status = if (it.spec is QueuedSpec.Page) DownloadStatus.EXTRACTING else DownloadStatus.DOWNLOADING,
                    progress = -1f, progressDetail = null, errorMessage = null)) }
        }
        return claim
    }

    fun hasQueued(): Boolean = _tasks.value.any { it.status == DownloadStatus.QUEUED }
    fun pauseReason(): QueuePause? = _tasks.value.firstNotNullOfOrNull { it.queuePause }
    fun activeCount(): Int = _tasks.value.count { it.status.isActive }
    fun get(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    /** Record metadata never changes an already selected output destination or existing public file. */
    fun editMetadata(id: String, metadata: EpisodeMetadata): Boolean {
        var edited = false
        mutate { records -> records.map { record ->
            if (record.task.id != id || record.task.sourceKind == SourceKind.GENERAL ||
                record.task.status.isActive || record.deleteRequested) record else {
                edited = true
                record.copy(
                    task = metadata.applyTo(record.task),
                    spec = when (val spec = record.spec) {
                        is QueuedSpec.Page -> spec.copy(page = spec.page.copy(
                            title = metadata.title, seriesTitle = metadata.seriesTitle,
                            seasonNumber = metadata.seasonNumber, episodeNumber = metadata.episodeNumber,
                            metadataOverride = metadata,
                        ))
                        is QueuedSpec.Resolved -> spec.copy(request = spec.request.copy(item = metadata.applyTo(spec.request.item)))
                    },
                    resolvedRequest = record.resolvedRequest?.let { it.copy(item = metadata.applyTo(it.item)) },
                )
            }
        } }
        return edited
    }

    fun resolved(claim: DownloadClaim, request: DownloadRequest) = update(claim) { record ->
        val item = request.item
        record.copy(resolvedRequest = request, task = record.task.copy(
            title = item.title, thumbnailUrl = item.thumbnailUrl ?: record.task.thumbnailUrl,
            pageUrl = item.source.pageUrl, sourceKind = item.source.kind, originalTitle = item.originalTitle,
            seriesTitle = item.seriesTitle ?: record.task.seriesTitle,
            seasonNumber = item.seasonNumber ?: record.task.seasonNumber,
            episodeNumber = item.episodeNumber ?: record.task.episodeNumber,
            status = DownloadStatus.DOWNLOADING, progress = -1f))
    }
    fun extracting(claim: DownloadClaim, detail: String? = null) = update(claim) {
        it.copy(task = it.task.copy(status = DownloadStatus.EXTRACTING, progress = -1f, progressDetail = detail))
    }

    fun setProgress(claim: DownloadClaim, progress: Float, detail: String? = null) = update(claim) {
        it.copy(task = it.task.copy(status = DownloadStatus.DOWNLOADING, progress = progress, progressDetail = detail))
    }
    fun setProgress(claim: DownloadClaim, progress: DownloadProgress) = update(claim) {
        it.copy(task = it.task.copy(
            status = if (progress.phase == DownloadPhase.DOWNLOADING) DownloadStatus.DOWNLOADING else DownloadStatus.PROCESSING,
            progress = progress.fraction, progressDetail = progress.detail,
        ))
    }

    /** A global condition is anchored in a durable task until the user can resume the queue. */
    fun pause(reason: QueuePause, taskId: String? = null): QueuePause? {
        var applied: QueuePause? = null
        mutate { records ->
            val existing = records.firstOrNull { it.task.queuePause?.kind == QueuePauseKind.STORAGE }
            val effective = if (reason.kind == QueuePauseKind.SYSTEM) existing?.task?.queuePause ?: reason else reason
            val target = existing?.takeIf { reason.kind == QueuePauseKind.SYSTEM }
                ?: records.firstOrNull { it.task.id == taskId && it.task.status != DownloadStatus.COMPLETED }
                ?: records.firstOrNull { !it.task.status.isTerminal }
                ?: return@mutate records
            applied = effective
            records.map { if (it.task.id == target.task.id) it.copy(task = it.task.copy(queuePause = effective)) else it }
        }
        return applied
    }

    fun resume(systemOnly: Boolean = false) = mutate { records -> records.map {
        val pause = it.task.queuePause
        if (pause == null || systemOnly && pause.kind != QueuePauseKind.SYSTEM) it else it.copy(task = it.task.copy(
            queuePause = null, errorMessage = null,
            status = if (it.task.status == DownloadStatus.FAILED) DownloadStatus.QUEUED else it.task.status,
        ))
    } }
    fun processing(claim: DownloadClaim, detail: String = "正在封裝與儲存") = update(claim) {
        it.copy(task = it.task.copy(status = DownloadStatus.PROCESSING, progress = -1f, progressDetail = detail))
    }
    fun complete(claim: DownloadClaim, outputUri: String, mimeType: String): Boolean {
        var committed = false
        update(claim) {
            committed = true
            it.copy(task = it.task.copy(status = DownloadStatus.COMPLETED, progress = 1f,
                outputUri = outputUri, mimeType = mimeType, errorMessage = null, progressDetail = null))
        }
        if (committed) deleteScratch(claim.id)
        return committed
    }
    fun setPreview(id: String, path: String) = mutate { records -> records.map {
        if (it.task.id == id) it.copy(task = it.task.copy(previewPath = path)) else it
    } }
    fun fail(claim: DownloadClaim, message: String) = update(claim) {
        it.copy(task = it.task.copy(status = DownloadStatus.FAILED, errorMessage = message, progressDetail = null))
    }

    /** Cancellation retains resumable bytes; explicit deletion removes them after native exit. */
    fun cancel(id: String) = mutate { records -> records.map { record ->
        if (record.task.id != id || record.task.status.isTerminal) record else record.copy(task = record.task.copy(
            cancelRequested = record.task.status.isActive,
            status = if (record.task.status.isActive) record.task.status else DownloadStatus.CANCELED))
    } }
    fun acknowledgeStop(claim: DownloadClaim, interrupted: Boolean = false) {
        var delete = false
        mutate { records -> records.mapNotNull { record ->
            if (record.task.id != claim.id || record.attempt != claim.attempt || !record.task.status.isActive) record
            else if (record.deleteRequested) {
                delete = true
                record.copy(task = record.task.copy(status = DownloadStatus.CANCELED, cancelRequested = false))
            }
            else record.copy(task = record.task.copy(
                status = if (record.task.cancelRequested) DownloadStatus.CANCELED else if (interrupted) DownloadStatus.QUEUED else DownloadStatus.FAILED,
                cancelRequested = false, progressDetail = if (interrupted) record.task.queuePause?.message ?: "等待恢復下載" else null))
        } }
        if (delete) finishDeletion(claim.id)
    }
    fun retry(id: String): Boolean = retryMatching {
        it.id == id && it.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELED)
    } > 0

    /** Reset the current failed set atomically; repeated taps cannot enqueue duplicates. */
    fun retryAllFailed(): Int = retryMatching { it.status == DownloadStatus.FAILED }

    private fun retryMatching(matches: (DownloadTask) -> Boolean): Int {
        var retried = 0
        mutate { records -> records.map {
            if (it.deleteRequested || !matches(it.task)) it else {
                retried++
                it.copy(task = it.task.copy(status = DownloadStatus.QUEUED, progress = -1f, errorMessage = null,
                    progressDetail = null, cancelRequested = false, queuePause = null))
            }
        } }
        return retried
    }
    fun remove(id: String) {
        var deleted = false
        mutate { records -> records.mapNotNull {
            when {
                it.task.id != id -> it
                it.task.status.isActive -> it.copy(deleteRequested = true, task = it.task.copy(cancelRequested = true))
                else -> { deleted = true; it.copy(deleteRequested = true) }
            }
        } }
        if (deleted) finishDeletion(id)
    }
    fun clearFinished() { _tasks.value.filter { it.status.isTerminal }.forEach { remove(it.id) } }
    fun workDirectory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        return File(workRoot, id)
    }
    private fun deleteScratch(id: String): Boolean = runCatching {
        val directory = workDirectory(id)
        beforeDelete(id) && (!directory.exists() || directory.deleteRecursively())
    }.getOrDefault(false)

    /** Keep the deletion intent durable until native exit and successful private-file cleanup. */
    private fun finishDeletion(id: String) = synchronized(lock) {
        val record = store.read().firstOrNull { it.task.id == id } ?: return@synchronized
        if (!record.deleteRequested || record.task.status.isActive || !deleteScratch(id)) return@synchronized
        mutate { records -> records.filterNot { it.task.id == id && it.deleteRequested && !it.task.status.isActive } }
    }
    private fun update(claim: DownloadClaim, change: (QueuedDownload) -> QueuedDownload) = mutate { records -> records.map {
        if (it.task.id == claim.id && it.attempt == claim.attempt && it.task.status.isActive && !it.task.cancelRequested) change(it) else it
    } }
    private fun mutate(change: (List<QueuedDownload>) -> List<QueuedDownload>) = synchronized(lock) {
        _tasks.value = store.transact { transferDeletedPause(change(it)) }
            .filterNot { it.deleteRequested && !it.task.status.isActive }.map { it.task }
    }

    /** A hidden deletion tombstone cannot be the only owner of a global pause. */
    private fun transferDeletedPause(records: List<QueuedDownload>): List<QueuedDownload> {
        fun hidden(record: QueuedDownload) = record.deleteRequested && !record.task.status.isActive
        val removed = records.filter(::hidden).mapNotNull { it.task.queuePause }
        if (removed.isEmpty()) return records
        val visible = records.filterNot(::hidden)
        val target = visible.firstOrNull { it.task.queuePause != null }
            ?: visible.firstOrNull { !it.task.status.isTerminal }
        val reason = (removed + visible.mapNotNull { it.task.queuePause })
            .firstOrNull { it.kind == QueuePauseKind.STORAGE } ?: removed.first()
        return records.map { record -> when {
            hidden(record) -> record.copy(task = record.task.copy(queuePause = null))
            record.task.id == target?.task?.id -> record.copy(task = record.task.copy(queuePause = reason))
            else -> record
        } }
    }

    private fun specFor(request: DownloadRequest): QueuedSpec {
        val item = request.item
        if (item.source.kind == SourceKind.GENERAL) return QueuedSpec.Resolved(request)
        val selection = when (val s = request.selection) {
            is FormatSelection.SpecificFormat -> s.format.height?.let { FormatSelection.CappedVideo(it) } ?: FormatSelection.BestVideo
            else -> s
        }
        return QueuedSpec.Page(EpisodePage(item.source.pageUrl, item.source.kind, item.source.originalUrl,
            title = item.title, seriesTitle = item.seriesTitle, seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber, thumbnailUrl = item.thumbnailUrl), selection)
    }
    private fun taskFor(id: String, position: Long, spec: QueuedSpec): DownloadTask {
        val page = (spec as? QueuedSpec.Page)?.page
        val request = (spec as? QueuedSpec.Resolved)?.request
        val item = request?.item
        val selection = (spec as? QueuedSpec.Page)?.selection ?: requireNotNull(request).selection
        val task = DownloadTask(id = id, title = page?.title ?: item?.title ?: "等待解析劇集",
            sourceUrl = page?.originalUrl ?: requireNotNull(item).source.originalUrl,
            thumbnailUrl = page?.thumbnailUrl ?: item?.thumbnailUrl, formatLabel = labelFor(selection),
            status = DownloadStatus.QUEUED, progress = -1f, outputUri = null, mimeType = null, errorMessage = null,
            createdAt = System.currentTimeMillis(), sourceKind = page?.kind ?: requireNotNull(item).source.kind,
            pageUrl = page?.playbackUrl ?: requireNotNull(item).source.pageUrl,
            seriesTitle = page?.seriesTitle ?: item?.seriesTitle, seasonNumber = page?.seasonNumber ?: item?.seasonNumber,
            episodeNumber = page?.episodeNumber ?: item?.episodeNumber, originalTitle = item?.originalTitle, queuePosition = position,
            lineId = page?.lineId ?: page?.let { com.changyow.mediadler.core.extract.EightMapleUrl.playbackOrNull(it.playbackUrl)?.line?.toString() },
            lineName = page?.lineName)
        return page?.metadataOverride?.applyTo(task) ?: task
    }
    private fun pageKey(page: EpisodePage): String {
        val uri = URI(page.playbackUrl)
        return "${page.kind}|${uri.path}|${page.lineId.orEmpty()}"
    }
    private fun labelFor(selection: FormatSelection): String = when (selection) {
        FormatSelection.BestVideo -> "Best"
        is FormatSelection.CappedVideo -> "${selection.maxHeight}p"
        is FormatSelection.Audio -> selection.audioFormat.ext.uppercase()
        is FormatSelection.SpecificFormat -> selection.format.label
        FormatSelection.ImageOriginal -> "Image"
    }
}
