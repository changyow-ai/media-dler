package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.AudioWindow
import com.changyow.mediadler.core.transcribe.WindowPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowPlannerTest {
    @Test fun emptyWhenNonPositiveDuration() {
        assertTrue(WindowPlanner.plan(0).isEmpty())
        assertTrue(WindowPlanner.plan(-5).isEmpty())
    }

    @Test fun singleWindowWhenShorterThanWindow() {
        assertEquals(
            listOf(AudioWindow(0, 90_000)),
            WindowPlanner.plan(90_000, windowMs = 600_000, overlapMs = 5_000),
        )
    }

    @Test fun exactlyWindowLengthStaysSingle() {
        assertEquals(
            listOf(AudioWindow(0, 600_000)),
            WindowPlanner.plan(600_000, windowMs = 600_000, overlapMs = 5_000),
        )
    }

    @Test fun splitsWithOverlapAndCoversWholeAudio() {
        val windows = WindowPlanner.plan(1_000, windowMs = 400, overlapMs = 100)
        // step = 300: [0,400) [300,700) [600,1000)
        assertEquals(
            listOf(AudioWindow(0, 400), AudioWindow(300, 700), AudioWindow(600, 1_000)),
            windows,
        )
        assertEquals(0, windows.first().startMs)
        assertEquals(1_000, windows.last().endMs)
    }

    @Test fun consecutiveWindowsOverlapByOverlapMs() {
        val windows = WindowPlanner.plan(1_000, windowMs = 400, overlapMs = 100)
        for (i in 1 until windows.size) {
            // start of next is 100ms before end of previous (until the final clamped window)
            val overlap = windows[i - 1].endMs - windows[i].startMs
            assertTrue(overlap >= 100, "windows should overlap by at least 100ms, was $overlap")
        }
    }

    @Test fun rejectsBadParams() {
        assertFailsWith<IllegalArgumentException> { WindowPlanner.plan(1_000, windowMs = 0) }
        assertFailsWith<IllegalArgumentException> { WindowPlanner.plan(1_000, windowMs = 100, overlapMs = 100) }
        assertFailsWith<IllegalArgumentException> { WindowPlanner.plan(1_000, windowMs = 100, overlapMs = -1) }
    }
}
