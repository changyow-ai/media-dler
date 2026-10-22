package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.BoundedAudioWindows
import com.changyow.mediadler.core.transcribe.BoundedAudioWindows.Action
import com.changyow.mediadler.core.transcribe.WindowPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedAudioWindowsTest {
    private fun windows(durationMs: Long?) = BoundedAudioWindows(durationMs, 60_000, 3_000, 16_000)

    @Test fun `short first window is never mistaken for overlap`() {
        val decision = windows(2_000).afterDecode(0, 32_000)
        assertEquals(Action.ACCEPT_LAST, decision.action)
        assertEquals(1, decision.totalWindows)
        assertFalse(decision.hasNext)
    }

    @Test fun `underreported duration continues bounded windows without checkpointing EOF`() {
        val windows = windows(10_000) // Actual PCM lasts 90 seconds, not the metadata's 10 seconds.
        val accepted = mutableListOf<Int>()
        for ((index, count) in listOf(960_000, 528_000, 0).withIndex()) {
            val decision = windows.afterDecode(index, count)
            assertEquals(0, decision.totalWindows)
            if (decision.action == Action.END_OF_INPUT) break
            assertEquals(Action.ACCEPT_MORE, decision.action)
            accepted.add(index + 1)
        }
        assertEquals(listOf(1, 2), accepted)
        assertEquals(57_000, windows.windowAt(1).startMs)
        assertEquals(117_000, windows.windowAt(1).endMs)
    }

    @Test fun `exact boundary probes but does not infer or checkpoint overlap-only tail`() {
        for (metadataMs in listOf(10_000L, 60_000L)) {
            val windows = windows(metadataMs)
            assertEquals(Action.ACCEPT_MORE, windows.afterDecode(0, 960_000).action)
            assertEquals(Action.END_OF_INPUT, windows.afterDecode(1, 48_000).action)
            // Even one new sample must not be discarded as a duplicate overlap.
            assertEquals(Action.ACCEPT_MORE, windows.afterDecode(1, 48_001).action)
        }
    }

    @Test fun `resume at or beyond metadata plan still reads real bounded audio`() {
        val windows = windows(10_000)
        assertEquals(0, windows.totalAt(1))
        assertEquals(0, windows.totalAt(2))
        assertEquals(114_000, windows.windowAt(2).startMs)
        assertEquals(Action.ACCEPT_MORE, windows.afterDecode(1, 528_000).action)
        assertEquals(Action.END_OF_INPUT, windows.afterDecode(2, 0).action)
        // Resume immediately after a completed short final window safely finds no new audio.
        assertEquals(Action.END_OF_INPUT, windows.afterDecode(1, 32_000).action)
    }

    @Test fun `empty expected windows fail while confirmed out-of-range empty is EOF`() {
        val windows = windows(120_000)
        for (index in 0..2) {
            assertEquals(Action.MISSING_EXPECTED_AUDIO, windows.afterDecode(index, 0).action)
        }
        assertEquals(Action.END_OF_INPUT, windows.afterDecode(3, 0).action)
        assertEquals(Action.MISSING_EXPECTED_AUDIO, windows(null).afterDecode(0, 0).action)
        assertEquals(Action.END_OF_INPUT, windows(null).afterDecode(1, 0).action)
    }

    @Test fun `unknown duration skips only later overlap-only windows`() {
        val windows = windows(null)
        assertEquals(Action.ACCEPT_MORE, windows.afterDecode(0, 32_000).action)
        assertEquals(Action.END_OF_INPUT, windows.afterDecode(1, 48_000).action)
        assertEquals(Action.ACCEPT_MORE, windows.afterDecode(1, 48_001).action)
    }

    @Test fun `metadata count and checkpoint indices match existing planner`() {
        for ((size, overlap) in listOf(60_000L to 3_000L, 300_000L to 5_000L, 600_000L to 5_000L)) {
            for (duration in listOf(1L, size, size + 1, 2 * size - overlap, 2 * size - overlap + 1, 3_600_000L)) {
                val planned = WindowPlanner.plan(duration, size, overlap)
                val windows = BoundedAudioWindows(duration, size, overlap, 16_000)
                assertEquals(planned.size, windows.totalAt(0))
                for (index in planned.indices) {
                    assertEquals(planned[index].startMs, windows.windowAt(index).startMs)
                    assertEquals(size, windows.windowAt(index).durationMs)
                }
            }
        }
    }

    @Test fun `one sample resampling roundoff cannot hide a full final window`() {
        val decision = windows(10_000).afterDecode(0, 959_999)
        assertTrue(decision.hasNext)
        assertEquals(0, decision.totalWindows)
        assertEquals(Action.END_OF_INPUT, windows(10_000).afterDecode(1, 47_999).action)
    }

    @Test fun `later underreported window tolerates codec frame alignment shortfall`() {
        val windows = windows(70_000) // Actual audio lasts 150 seconds; metadata plans only 2 windows.
        assertEquals(2, windows.afterDecode(0, 960_000).totalWindows)
        for (missingSamples in listOf(1, 320, 4_000)) { // up to 250 ms, not a silence threshold
            val decision = windows.afterDecode(1, 960_000 - missingSamples)
            assertEquals(Action.ACCEPT_MORE, decision.action)
            assertEquals(0, decision.totalWindows)
        }
        assertEquals(Action.ACCEPT_MORE, windows.afterDecode(2, 576_000).action)
        assertEquals(Action.END_OF_INPUT, windows.afterDecode(3, 0).action)
    }
}
