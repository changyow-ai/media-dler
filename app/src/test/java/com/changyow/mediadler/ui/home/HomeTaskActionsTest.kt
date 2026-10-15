package com.changyow.mediadler.ui.home

import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.DownloadTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    private fun task(id: String = "task") = DownloadTask(id, "Video", "https://media.example/watch",
        null, "Best", DownloadStatus.QUEUED, -1f, null, null, null, 0)
}
