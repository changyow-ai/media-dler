package com.changyow.mediadler.download

import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.QueuePause
import com.changyow.mediadler.core.model.QueuePauseKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadRecoveryPolicyTest {
    @Test fun queuedActiveAndNetworkWaitingTasksRetainADurableRecoveryJob() {
        for (status in listOf(DownloadStatus.QUEUED, DownloadStatus.EXTRACTING,
            DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING)) {
            assertTrue(hasRecoverableDownloads(listOf(task(status))))
        }
        assertTrue(hasRecoverableDownloads(listOf(task(DownloadStatus.QUEUED, QueuePauseKind.NETWORK))))
    }

    @Test fun backgroundRecoveryNeverOverridesUserActionOrRevivesFinishedTasks() {
        assertFalse(hasRecoverableDownloads(emptyList()))
        for (status in listOf(DownloadStatus.COMPLETED, DownloadStatus.CANCELED, DownloadStatus.FAILED, DownloadStatus.PAUSED)) {
            assertFalse(hasRecoverableDownloads(listOf(task(status))))
        }
        for (kind in listOf(QueuePauseKind.SYSTEM, QueuePauseKind.STORAGE)) {
            assertFalse(hasRecoverableDownloads(listOf(task(DownloadStatus.QUEUED), task(DownloadStatus.FAILED, kind))))
        }
    }

    @Test fun manuallyPausedItemsDoNotScheduleRecoveryButOtherQueuedItemsStillDo() {
        assertFalse(hasRecoverableDownloads(List(3) { task(DownloadStatus.PAUSED) }))
        assertFalse(hasRecoverableDownloads(listOf(task(DownloadStatus.PAUSED, QueuePauseKind.NETWORK))))
        assertTrue(hasRecoverableDownloads(listOf(task(DownloadStatus.PAUSED), task(DownloadStatus.QUEUED))))
    }

    private fun task(status: DownloadStatus, pause: QueuePauseKind? = null) = DownloadTask(
        id = "task-$status-$pause", title = "Episode", sourceUrl = "https://example.test/video", thumbnailUrl = null,
        formatLabel = "Best", status = status, progress = -1f, outputUri = null, mimeType = null,
        errorMessage = null, createdAt = 0, queuePause = pause?.let { QueuePause(it, "paused") },
    )
}
