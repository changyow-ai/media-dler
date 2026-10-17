package com.changyow.mediadler.ui.settings

import java.math.BigDecimal

internal enum class DownloadRateUnit(val bytes: Long, val label: String) {
    KB(1_000, "KB/s"), MB(1_000_000, "MB/s"),
}

internal val downloadRatePresets: List<Long?> = listOf(null, 256_000L, 512_000L, 1_000_000L, 2_000_000L, 5_000_000L)

/** Parse decimal SI units without floating-point rounding, overflow, or CLI suffixes. */
internal fun parseDownloadRate(amount: String, unit: DownloadRateUnit): Long? {
    val normalized = amount.trim().replace(',', '.')
    if (!normalized.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) return null
    return try {
        BigDecimal(normalized).multiply(BigDecimal.valueOf(unit.bytes)).longValueExact().takeIf { it > 0 }
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}

internal fun downloadRateUnit(bytes: Long): DownloadRateUnit =
    if (bytes >= DownloadRateUnit.MB.bytes) DownloadRateUnit.MB else DownloadRateUnit.KB

internal fun downloadRateAmount(bytes: Long, unit: DownloadRateUnit): String =
    BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(unit.bytes)).stripTrailingZeros().toPlainString()

internal fun formatDownloadRate(bytes: Long?): String {
    if (bytes == null) return "不限速"
    val unit = downloadRateUnit(bytes)
    return "${downloadRateAmount(bytes, unit)} ${unit.label}"
}
