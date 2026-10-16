package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.DownloadFailurePolicy
import com.changyow.mediadler.core.download.LowStorageException
import com.changyow.mediadler.core.download.YtDlpProgressParser
import com.changyow.mediadler.core.model.DownloadPhase
import com.changyow.mediadler.core.model.QueuePauseKind
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpProgressParserTest {
    @Test fun resumedFragmentReportsTheRollbackBoundaryWithoutInventingAByteCount() {
        val progress = YtDlpProgressParser.parse("media-dler-resume:116")!!
        assertEquals(DownloadPhase.DOWNLOADING, progress.phase)
        assertEquals("正在從片段 116 接續（回退一段重新下載）", progress.detail)
        assertNull(progress.downloadedBytes)
        assertEquals(-1f, progress.fraction)
        assertNull(YtDlpProgressParser.parse("media-dler-resume:0"))
        assertNull(YtDlpProgressParser.parse("media-dler-resume:bad"))
    }
    @Test fun knownTotalShowsActualBytesAndPhaseLocalFraction() {
        val progress = YtDlpProgressParser.parse("""media-dler-progress:{"status":"downloading","downloaded_bytes":500000,"total_bytes":1000000}""")!!
        assertEquals(.5f, progress.fraction)
        assertEquals(500000L, progress.downloadedBytes)
        assertEquals("500.0 KB / 1.0 MB", progress.detail)
    }

    @Test fun estimatedOrMissingTotalDoesNotBecomeAFakePercentage() {
        val progress = YtDlpProgressParser.parse("""media-dler-progress:{"status":"downloading","downloaded_bytes":1234000,"total_bytes":"NA","total_bytes_estimate":10000000,"fragment_index":3,"fragment_count":20}""")!!
        assertEquals(-1f, progress.fraction)
        assertNull(progress.totalBytes)
        assertEquals("已下載 1.2 MB · 片段 3/20", progress.detail)
    }

    @Test fun finishedDownloadStillHasAProcessingStageBeforePublicCompletion() {
        for (line in listOf("""media-dler-progress:{"status":"finished","downloaded_bytes":100,"total_bytes":100}""",
            "media-dler-phase:started", "[Merger] Merging formats into private output")) {
            val progress = YtDlpProgressParser.parse(line)!!
            assertEquals(DownloadPhase.PROCESSING, progress.phase)
            assertEquals(-1f, progress.fraction)
        }
    }

    @Test fun irrelevantOrBrokenOutputCannotReuseAStaleSdkPercentage() {
        for (line in listOf("[download] Destination: media.mp4", "media-dler-progress:bad json", "other log",
            """media-dler-progress:{"status":"error"}""")) assertNull(YtDlpProgressParser.parse(line))
        val unknown = YtDlpProgressParser.parse("""media-dler-progress:{"status":"downloading","downloaded_bytes":-1,"total_bytes":0}""")!!
        assertEquals(-1f, unknown.fraction)
        assertEquals("正在下載", unknown.detail)
    }

    @Test fun globalStorageFailurePausesButOrdinaryPageFailureCanContinue() {
        for (error in listOf(LowStorageException(), IOException("write failed: ENOSPC"), IOException("[Errno 28] No space left on device"))) {
            assertEquals(QueuePauseKind.STORAGE, DownloadFailurePolicy.pauseFor(error)?.kind)
        }
        assertNull(DownloadFailurePolicy.pauseFor(IOException("connection reset")))
        assertTrue(DownloadFailurePolicy.shouldRefresh(IOException("HTTP Error 403: Forbidden")))
        assertTrue(DownloadFailurePolicy.shouldRefresh(IOException("HTTP 410 Gone")))
        assertFalse(DownloadFailurePolicy.shouldRefresh(IOException("episode403.mp4")))
        assertFalse(DownloadFailurePolicy.shouldRefresh(IOException("HTTP Error 500")))
    }
}
