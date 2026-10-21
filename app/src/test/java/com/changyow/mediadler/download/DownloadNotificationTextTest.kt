package com.changyow.mediadler.download

import com.changyow.mediadler.core.model.DownloadPhase
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.ItemInterruption
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadNotificationTextTest {
    @Test fun analysisAndTransferKeepTheirLabelsWhenDetailsContainAnalysisProgress() {
        val detail = "候選已處理 4/12 · 未確認 2"
        val analyzing = task().copy(progressPhase = DownloadPhase.AD_ANALYSIS, progressDetail = detail)
        assertEquals("廣告分析中\n$detail", downloadProgressText(analyzing))
        assertEquals("下載中 42%\n$detail", downloadProgressText(analyzing.copy(progressPhase = DownloadPhase.DOWNLOADING)))
        assertEquals("下載中 42%\n$detail", downloadProgressText(analyzing.copy(progressPhase = null)))
        assertEquals("處理中\n$detail", downloadProgressText(analyzing.copy(status = DownloadStatus.PROCESSING)))
    }

    @Test fun interruptionAndCancellationReplaceAnalysisDetail() {
        val analyzing = task().copy(progressPhase = DownloadPhase.AD_ANALYSIS, progressDetail = "候選已處理 4/12")
        val labels = mapOf(
            ItemInterruption.MANUAL_PAUSE to "正在暫停…",
            ItemInterruption.PRIORITY_REQUEUE to "正在切換下載…",
            ItemInterruption.SKIP_AD_ANALYSIS to "正在略過廣告分析…",
        )
        for ((interruption, label) in labels) {
            assertEquals(label, downloadProgressText(analyzing.copy(interruption = interruption)))
            assertEquals("正在取消…", downloadProgressText(analyzing.copy(interruption = interruption, cancelRequested = true)))
        }
    }

    @Test fun pausedAnalysisDetailKeepsItsExistingPastProgressLabel() {
        val detail = "候選已處理 4/12"
        assertEquals("已暫停 42%\n暫停前進度：\n$detail",
            downloadProgressText(task().copy(status = DownloadStatus.PAUSED,
                progressPhase = DownloadPhase.AD_ANALYSIS, progressDetail = detail)))
        assertEquals("已暫停 42%", downloadProgressText(task().copy(status = DownloadStatus.PAUSED,
            progressDetail = "下載中")))
    }

    private fun task() = DownloadTask("task", "Video", "https://media.example/watch", null, "Best",
        DownloadStatus.DOWNLOADING, .42f, null, null, null, 0)
}
