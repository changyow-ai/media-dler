package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.DownloadFailurePolicy
import com.changyow.mediadler.core.download.LowStorageException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadFailurePolicyTest {
    @Test fun connectionFailureDescribesTheSourceWithoutClaimingTheVideoWasRemoved() {
        val message = DownloadFailurePolicy.sourceFailureMessage(IOException("Connection refused by cdn.example"))
        assertTrue(message.contains("此來源目前無法載入"))
        assertTrue(message.contains("Connection refused by cdn.example"))
        assertFalse(message.contains("已不存在"))
        assertFalse(message.contains("等待網路恢復"))
    }

    @Test fun missingAndGoneResponsesDescribeAnUnavailableLinkButForbiddenDoesNot() {
        for (status in listOf(404, 410)) {
            val message = DownloadFailurePolicy.sourceFailureMessage(IOException("HTTP Error $status"))
            assertTrue(message.contains("已不存在或連結已失效"))
            assertTrue(message.contains("HTTP Error $status"))
        }
        val denied = DownloadFailurePolicy.sourceFailureMessage(IOException("HTTP Error 403"))
        assertTrue(denied.contains("拒絕存取"))
        assertFalse(denied.contains("已不存在"))
    }

    @Test fun certificateFailurePreservesItsReasonWithoutSuggestingTlsBypass() {
        for (reason in listOf("certificate verify failed", "self signed certificate",
            "self-signed certificate", "Trust anchor for certification path not found")) {
            val message = DownloadFailurePolicy.sourceFailureMessage(IOException(reason))
            assertTrue(message.contains("安全憑證驗證失敗"))
            assertTrue(message.contains(reason))
        }
    }

    @Test fun storageAndProcessingErrorsKeepTheirOwnMessages() {
        val storage = LowStorageException()
        assertEquals(storage.message, DownloadFailurePolicy.sourceFailureMessage(storage))
        assertEquals("封裝驗證失敗", DownloadFailurePolicy.sourceFailureMessage(IOException("封裝驗證失敗")))
    }
}
