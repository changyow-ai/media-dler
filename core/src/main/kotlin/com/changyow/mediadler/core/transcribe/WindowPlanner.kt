package com.changyow.mediadler.core.transcribe

/** A half-open time window `[startMs, endMs)` of the source audio. */
data class AudioWindow(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Plans how to slice long audio for streaming transcription. A short clip stays a single window;
 * a long one is cut into [windowMs]-sized windows that overlap by [overlapMs] so a word straddling
 * a boundary still appears whole in one window. The overlap is de-duplicated afterwards by
 * [SegmentMerge]. Slicing by time (not loading the whole decoded PCM) is what keeps memory bounded
 * for hour-long audio on-device.
 */
object WindowPlanner {
    const val DEFAULT_WINDOW_MS = 10 * 60_000L // 10 min
    const val DEFAULT_OVERLAP_MS = 5_000L      // 5 s

    fun plan(
        totalMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        overlapMs: Long = DEFAULT_OVERLAP_MS,
    ): List<AudioWindow> {
        require(windowMs > 0) { "windowMs must be > 0" }
        require(overlapMs in 0 until windowMs) { "overlapMs must be in [0, windowMs)" }
        if (totalMs <= 0) return emptyList()
        if (totalMs <= windowMs) return listOf(AudioWindow(0, totalMs))

        val windows = ArrayList<AudioWindow>()
        val step = windowMs - overlapMs
        var start = 0L
        while (start < totalMs) {
            val end = minOf(start + windowMs, totalMs)
            windows.add(AudioWindow(start, end))
            if (end >= totalMs) break
            start += step
        }
        return windows
    }

    /**
     * The [index]-th window for open-ended streaming when the total duration is unknown:
     * `[index*step, index*step + windowMs)` with the same overlap as [plan]. The caller decodes
     * windows in order and stops at the first window that yields no samples (past end-of-stream),
     * which keeps memory bounded even without container duration metadata.
     */
    fun openWindow(
        index: Int,
        windowMs: Long = DEFAULT_WINDOW_MS,
        overlapMs: Long = DEFAULT_OVERLAP_MS,
    ): AudioWindow {
        require(windowMs > 0) { "windowMs must be > 0" }
        require(overlapMs in 0 until windowMs) { "overlapMs must be in [0, windowMs)" }
        val start = index.toLong() * (windowMs - overlapMs)
        return AudioWindow(start, start + windowMs)
    }
}
