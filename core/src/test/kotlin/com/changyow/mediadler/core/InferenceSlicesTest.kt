package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.InferenceSlices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InferenceSlicesTest {
    @Test fun everySampleAndOneSampleTailSurvive() {
        for (n in listOf(0, 1, 15 * 16000, 15 * 16000 + 1, 60 * 16000)) {
            val ranges = InferenceSlices.plan(n, 15 * 16000, 2 * 16000)
            if (n == 0) { assertTrue(ranges.isEmpty()); continue }
            assertEquals(0, ranges.first().start)
            assertEquals(n, ranges.last().endExclusive)
            assertTrue(ranges.all { it.endExclusive > it.start && it.endExclusive - it.start <= 15 * 16000 })
            ranges.zipWithNext().forEach { (a, b) ->
                assertEquals(a.endExclusive - 2 * 16000, b.start)
                assertTrue(b.endExclusive > a.endExclusive)
            }
        }
    }
    @Test fun exactEndDoesNotCreateDuplicateTail() {
        assertEquals(listOf(InferenceSlices.Slice(0, 240000)), InferenceSlices.plan(240000, 240000, 32000))
    }
    @Test fun senseVoiceWholeWindowDoesNotChange() {
        assertEquals(listOf(InferenceSlices.Slice(0, 960000)), InferenceSlices.plan(960000, 960000, 32000))
    }
    @Test fun invalidAndLargeBoundsCannotOverflowOrLoop() {
        assertFailsWith<IllegalArgumentException> { InferenceSlices.plan(-1, 10, 1) }
        assertFailsWith<IllegalArgumentException> { InferenceSlices.plan(20, 0, 0) }
        assertFailsWith<IllegalArgumentException> { InferenceSlices.plan(20, 10, 10) }
        val ranges = InferenceSlices.plan(Int.MAX_VALUE, Int.MAX_VALUE - 1, 0)
        assertEquals(Int.MAX_VALUE, ranges.last().endExclusive)
        assertEquals(2, ranges.size)
    }
}
