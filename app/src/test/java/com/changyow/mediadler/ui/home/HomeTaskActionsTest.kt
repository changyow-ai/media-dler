package com.changyow.mediadler.ui.home

import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.ItemInterruption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeTaskActionsTest {
    @Test fun unfinishedTasksAppearInFifoOrderBeforeNewestFirstHistoryAndLiveRecordsWin() {
        val first = task("first").copy(queuePosition = 1, createdAt = 10)
        val second = task("second").copy(queuePosition = 2, createdAt = 20)
        val old = task("old").copy(status = DownloadStatus.COMPLETED, createdAt = 30)
        val recent = task("recent").copy(status = DownloadStatus.FAILED, createdAt = 40)
        val merged = mergeHomeTasks(listOf(second, first), listOf(recent, old, first.copy(title = "stale")))
        assertEquals(listOf(first, second, recent, old), merged)
    }

    @Test fun completedVideoOrAudioPrefersItsExistingLocalOutput() {
        for (mime in listOf("video/mp4", "audio/m4a")) {
            val task = task().copy(status = DownloadStatus.COMPLETED, outputUri = "content://media/public/42", mimeType = mime)
            assertEquals(TranscriptSource("content://media/public/42", false), transcriptSourceFor(task))
        }
    }

    @Test fun unfinishedMediaUsesPageRatherThanTemporaryStreamOrOriginalRedirect() {
        val task = task().copy(sourceUrl = "https://share.example/original", pageUrl = "https://play.8maple.st/play/OD4C-2-4.html")
        assertEquals(TranscriptSource(task.pageUrl, true), transcriptSourceFor(task))
    }

    @Test fun imagesAndInvalidPageUrlsDoNotOfferTranscription() {
        assertNull(transcriptSourceFor(task().copy(formatLabel = "Image")))
        assertNull(transcriptSourceFor(task().copy(mimeType = "image/jpeg", status = DownloadStatus.COMPLETED, outputUri = "content://image/42")))
        assertNull(transcriptSourceFor(task().copy(pageUrl = "javascript:alert(1)")))
    }

    @Test fun pauseAndImmediateActionsOnlyApplyToTheirSupportedPhases() {
        for (status in DownloadStatus.entries) {
            val task = task().copy(status = status)
            assertEquals(status in setOf(DownloadStatus.QUEUED, DownloadStatus.EXTRACTING, DownloadStatus.DOWNLOADING), canPauseTask(task))
            assertEquals(status == DownloadStatus.PAUSED, canResumeTask(task))
            assertEquals(status in setOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED), canDownloadTaskNow(task))
        }
    }

    @Test fun interruptionsDisableRepeatedActionsAndShowWhatIsHappening() {
        for (interruption in ItemInterruption.entries) {
            for (status in listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)) {
                val task = task().copy(status = status, interruption = interruption)
                assertFalse(taskControlsAvailable(task))
                assertFalse(canPauseTask(task))
                assertFalse(canResumeTask(task))
                assertFalse(canDownloadTaskNow(task))
                assertEquals(if (interruption == ItemInterruption.MANUAL_PAUSE) "正在暫停…" else "正在切換下載…",
                    taskStatusText(task, 1))
                assertEquals("正在取消…", taskStatusText(task.copy(cancelRequested = true), 1))
            }
        }
        assertFalse(canPauseTask(task().copy(cancelRequested = true)))
    }

    @Test fun pausedProgressIsVisibleAndOnlyConfirmedNonresumableSourcesPromiseRestart() {
        val paused = task().copy(status = DownloadStatus.PAUSED, progress = .42f, progressDetail = "下載中")
        assertEquals("已暫停 42%", taskStatusText(paused, null))
        assertEquals("已暫停", taskStatusText(paused.copy(progress = -1f), null))
        assertNull(taskProgressDetail(paused))
        assertNull(taskProgressDetail(paused.copy(canResume = true)))
        assertEquals("此來源不支援續傳，繼續時會重新下載", taskProgressDetail(paused.copy(canResume = false)))
    }

    @Test fun manuallyPausedTasksStayVisibleButDoNotConsumeSchedulePositions() {
        val paused = task("paused").copy(status = DownloadStatus.PAUSED, queuePosition = 1)
        val queued = task("queued").copy(queuePosition = 2)
        val active = task("active").copy(status = DownloadStatus.DOWNLOADING, queuePosition = 0)
        val merged = mergeHomeTasks(listOf(paused, queued, active), emptyList())
        assertTrue(paused in merged)
        assertEquals(listOf(active, queued), scheduledHomeTasks(merged))
        assertEquals("排隊中 · 排程第 2 項", taskStatusText(queued, 2))
    }

    private fun task(id: String = "task") = DownloadTask(id, "Video", "https://media.example/watch",
        null, "Best", DownloadStatus.QUEUED, -1f, null, null, null, 0)
}
