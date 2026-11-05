package com.changyow.mediadler.core.sharing

/** A fixed, same-boot lifetime. File reads never change this deadline. */
data class WebShareSession(
    val startedElapsedMillis: Long,
    val expiresAtElapsedMillis: Long,
    val bootCount: Int,
) {
    fun isValid(nowElapsedMillis: Long, currentBootCount: Int): Boolean =
        bootCount >= 0 && currentBootCount == bootCount &&
            startedElapsedMillis >= 0 && nowElapsedMillis >= startedElapsedMillis &&
            nowElapsedMillis < expiresAtElapsedMillis &&
            expiresAtElapsedMillis - startedElapsedMillis in DURATIONS_MINUTES.map { it * 60_000L }

    companion object {
        val DURATIONS_MINUTES = listOf(5, 10, 15, 20, 25, 30, 60)
        const val DEFAULT_MINUTES = 10
        fun create(minutes: Int, nowElapsedMillis: Long, bootCount: Int): WebShareSession {
            require(minutes in DURATIONS_MINUTES) { "分享時間需選擇 5～30 分鐘（每 5 分鐘）或 1 小時" }
            require(nowElapsedMillis >= 0 && bootCount >= 0)
            return WebShareSession(nowElapsedMillis, Math.addExact(nowElapsedMillis, minutes * 60_000L), bootCount)
        }
    }
}
