package com.changyow.mediadler.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadRateInputTest {
    @Test fun decimalUnitsBecomeExactBytes() {
        assertEquals(512_000L, parseDownloadRate("512", DownloadRateUnit.KB))
        assertEquals(1_500_000L, parseDownloadRate("1.5", DownloadRateUnit.MB))
        assertEquals(1_500_000L, parseDownloadRate(" 1,5 ", DownloadRateUnit.MB))
        assertEquals(500L, parseDownloadRate(".5", DownloadRateUnit.KB))
        assertEquals(1L, parseDownloadRate("0.000001", DownloadRateUnit.MB))
    }

    @Test fun invalidOrUnrepresentableRatesCannotBeSaved() {
        listOf("", " ", "0", "0.0", "-1", "+1", "NaN", "Infinity", "1e3", "1 MB/s", "1,2.3",
            "0.0000001", "9223372036854.775808").forEach {
            assertNull(parseDownloadRate(it, DownloadRateUnit.MB), "Unexpected valid rate: $it")
        }
        assertEquals(Long.MAX_VALUE, parseDownloadRate("9223372036854.775807", DownloadRateUnit.MB))
    }

    @Test fun displayAndEditingRoundTripWithoutPrecisionLoss() {
        assertEquals("不限速", formatDownloadRate(null))
        assertEquals("512 KB/s", formatDownloadRate(512_000))
        assertEquals("2 MB/s", formatDownloadRate(2_000_000))
        assertEquals("1.5 MB/s", formatDownloadRate(1_500_000))
        listOf(1L, 500L, 512_000L, 999_999L, 1_000_000L, 1_000_001L, Long.MAX_VALUE).forEach { bytes ->
            val unit = downloadRateUnit(bytes)
            assertEquals(bytes, parseDownloadRate(downloadRateAmount(bytes, unit), unit))
        }
    }
}
