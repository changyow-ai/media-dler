package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.AdAnalysisProgressAccumulator
import com.changyow.mediadler.core.model.DownloadPhase
import com.changyow.mediadler.core.model.DownloadProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdAnalysisProgressAccumulatorTest {
    @Test fun candidateCompletionImmediatelyFollowedByProbePreservesCounts() {
        val progress = AdAnalysisProgressAccumulator { 0 }
        progress.accept("HLS_AD_CANDIDATE 4 12")
        val next = progress.accept("HLS_AD_STAGE probe")!!
        assertTrue(next.detail!!.contains("候選已處理 4/12"))
        assertTrue(next.detail!!.contains("分析候選影音"))
        assertEquals(-1f, next.fraction)
        assertEquals(DownloadPhase.AD_ANALYSIS, next.phase)
    }

    @Test fun structuredOcrSnapshotsPreserveUnknownsAndTickDuringLongWork() {
        var now = 0L
        val progress = AdAnalysisProgressAccumulator { now }
        progress.accept("""HLS_AD_PROGRESS {"stage":"scan","completed":4,"total":12,"unknown":2,"ads":null,"elapsed_seconds":90}""")
        progress.accept("""HLS_AD_PROGRESS {"stage":"ocr"}""")
        now = 15_000
        val heartbeat = progress.current()
        assertEquals(DownloadPhase.AD_ANALYSIS, heartbeat.phase)
        val detail = heartbeat.detail!!
        assertTrue(detail.contains("候選已處理 4/12 · 未確認 2"))
        assertTrue(detail.contains("辨識候選文字"))
        assertTrue(detail.contains("累計分析 1分45秒"))
        assertTrue(detail.contains("保留未確認片段"))
        assertFalse(detail.contains("剩餘"))
        assertFalse(detail.contains("%"))
    }

    @Test fun restoredSnapshotImmediatelyShowsSavedCountersAndTime() {
        var now = 0L
        val progress = AdAnalysisProgressAccumulator { now }
        val restored = progress.accept("""HLS_AD_PROGRESS {"stage":"restoring","completed":8,"total":12,"unknown":1,"elapsed_seconds":150}""")!!
        assertTrue(restored.detail!!.contains("恢復既有分析"))
        assertTrue(restored.detail!!.contains("候選已處理 8/12"))
        assertTrue(restored.detail!!.contains("累計分析 2分30秒"))
        now = 10_000
        assertTrue(progress.current().detail!!.contains("累計分析 2分40秒"))
    }

    @Test fun completedSummarySurvivesMediaDownloadWithoutCountingDownloadTimeAsAnalysis() {
        var now = 0L
        val progress = AdAnalysisProgressAccumulator { now }
        progress.accept("""HLS_AD_PROGRESS {"stage":"scan","completed":12,"total":12,"unknown":1,"elapsed_seconds":150}""")
        val finished = progress.finish(skippedAds = 2, analysisComplete = false)
        assertEquals(DownloadPhase.AD_ANALYSIS, finished.phase)
        assertTrue(finished.detail!!.contains("已辨識廣告 2 段"))
        now = 100_000
        val downloading = progress.decorate(DownloadProgress(DownloadPhase.DOWNLOADING, .5f, "500 KB / 1 MB"))
        assertEquals(.5f, downloading.fraction)
        assertEquals(DownloadPhase.DOWNLOADING, downloading.phase)
        assertTrue(downloading.detail!!.startsWith("500 KB / 1 MB\n"))
        assertTrue(downloading.detail!!.contains("候選已處理 12/12 · 未確認 1"))
        assertTrue(downloading.detail!!.contains("累計分析 2分30秒"))
        assertTrue(downloading.detail!!.contains("保留未確認片段"))
    }

    @Test fun completionWithNoAdsAndUnknownTotalsDoNotInventProgress() {
        val progress = AdAnalysisProgressAccumulator { 0 }
        assertTrue(progress.current().detail!!.contains("候選進度未知"))
        assertTrue(progress.current().detail!!.contains("累計分析耗時待確認"))
        assertEquals(-1f, progress.current().fraction)
        progress.accept("""HLS_AD_PROGRESS {"stage":"complete","completed":0,"total":0,"unknown":0,"ads":0}""")
        assertTrue(progress.current().detail!!.contains("未辨識出可移除廣告"))
        val unsupported = AdAnalysisProgressAccumulator { 0 }
        unsupported.accept("""HLS_AD_PROGRESS {"stage":"complete","completed":0,"total":null,"unknown":null,"ads":0}""")
        assertTrue(unsupported.current().detail!!.contains("保留未確認片段"))
        assertFalse(unsupported.current().detail!!.contains("未辨識出可移除廣告"))
    }

    @Test fun structuredSnapshotsWinOverLegacyAndMalformedOrStaleEventsCannotResetCounts() {
        val progress = AdAnalysisProgressAccumulator { 0 }
        progress.accept("""HLS_AD_PROGRESS {"stage":"ocr","completed":4,"total":12,"unknown":2,"elapsed_seconds":90}""")
        assertNull(progress.accept("HLS_AD_STAGE probe"))
        assertNull(progress.accept("HLS_AD_CANDIDATE 1 2"))
        assertNull(progress.accept("HLS_AD_PROGRESS invalid"))
        assertNull(progress.accept("""HLS_AD_PROGRESS {"stage":"probe","completed":9,"total":2}"""))
        assertNull(progress.accept("HLS_AD_CANDIDATE 9 2"))
        assertNotNull(progress.accept("""HLS_AD_PROGRESS {"stage":"probe","completed":1,"total":12,"unknown":0,"elapsed_seconds":1}"""))
        assertTrue(progress.current().detail!!.contains("候選已處理 4/12 · 未確認 2"))
        assertTrue(progress.current().detail!!.contains("累計分析 1分30秒"))
    }

    @Test fun checkpointRecoveryShowsRetainedProgressAndExplainsInvalidatedSuffix() {
        val progress = AdAnalysisProgressAccumulator { 0 }
        val restored = progress.accept("""HLS_AD_PROGRESS {"stage":"restoring","completed":3,"total":12,"unknown":1,"elapsed_seconds":150,"checkpoint_reason":"checkpoint_invalid_candidate_3: checksum"}""")!!
        assertTrue(restored.detail!!.contains("候選已處理 3/12"))
        assertTrue(restored.detail!!.contains("累計分析 2分30秒"))
        assertTrue(restored.detail!!.contains("部分分析紀錄無效，已保留可用進度"))
        assertTrue(progress.finish(0, false).detail!!.contains("已保留可用進度"))
    }
}
