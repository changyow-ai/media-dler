package com.changyow.mediadler.core.transcribe

/**
 * Container duration is an estimate, not an EOF marker. Keep every decode bounded, but probe the
 * next overlapping window when the estimated last window fills its bound. An extension containing
 * only overlap is already committed audio, not another inference/checkpoint.
 */
class BoundedAudioWindows(
    durationMs: Long?,
    private val windowMs: Long,
    private val overlapMs: Long,
    sampleRate: Int,
) {
    init {
        require(windowMs > 0 && overlapMs in 0 until windowMs)
        require(sampleRate > 0 && windowMs <= Int.MAX_VALUE.toLong() * 1000 / sampleRate)
        require(windowMs * sampleRate >= 1000) { "window must contain at least one output sample" }
    }

    private val plannedCount = durationMs?.takeIf { it > 0 }?.let {
        // Same count as WindowPlanner.plan, without materialising a list for a long recording.
        if (it <= windowMs) 1 else ((it - windowMs - 1) / (windowMs - overlapMs) + 2)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    private val fullSamples = windowMs * sampleRate / 1000
    private val overlapSamples = overlapMs * sampleRate / 1000
    private val fullnessTolerance = minOf(fullSamples - 1, maxOf(1L, sampleRate / 4L))

    enum class Action { MISSING_EXPECTED_AUDIO, END_OF_INPUT, ACCEPT_LAST, ACCEPT_MORE }

    data class Decision(val action: Action, val totalWindows: Int) {
        val hasNext: Boolean get() = action == Action.ACCEPT_MORE
    }

    /** Zero means an unknown remaining duration, including a resume beyond the metadata plan. */
    fun totalAt(index: Int): Int {
        require(index >= 0)
        return plannedCount?.takeIf { index < it } ?: 0
    }

    fun windowAt(index: Int): AudioWindow {
        require(index >= 0)
        return WindowPlanner.openWindow(index, windowMs, overlapMs)
    }

    fun afterDecode(index: Int, sampleCount: Int): Decision {
        require(index >= 0 && sampleCount >= 0)
        val expected = plannedCount != null && index < plannedCount
        val total = totalAt(index)
        if (sampleCount == 0) {
            // Empty PCM is EOF only beyond the plan (or after the first unknown-duration window).
            val action = if (index == 0 || expected) Action.MISSING_EXPECTED_AUDIO else Action.END_OF_INPUT
            return Decision(action, total)
        }
        if (!expected && index > 0 && sampleCount <= overlapSamples) {
            return Decision(Action.END_OF_INPUT, 0)
        }
        // Seeking/codec-frame alignment can shorten PCM slightly even before real EOF. Probe within
        // 250 ms of the bound rather than losing later speech; an overlap-only probe is discarded.
        // This is a timing tolerance, never an energy/volume threshold for quiet speech.
        val full = sampleCount.toLong() >= fullSamples - fullnessTolerance
        val lastPlanned = expected && index == plannedCount!! - 1
        val more = !expected || !lastPlanned || full
        return Decision(
            if (more) Action.ACCEPT_MORE else Action.ACCEPT_LAST,
            if (!expected || (lastPlanned && full)) 0 else total,
        )
    }
}
