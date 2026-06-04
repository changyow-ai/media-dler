package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.SegmentMerge
import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentMergeTest {
    @Test fun emptyAndBlankParts() {
        assertEquals("", SegmentMerge.merge(emptyList()))
        assertEquals("", SegmentMerge.merge(listOf("", "   ", "\n")))
    }

    @Test fun singlePartTrimmed() {
        assertEquals("hello world", SegmentMerge.merge(listOf("  hello world  ")))
    }

    @Test fun dropsOverlappingSeam() {
        // window A ends with "...the quick brown fox", window B starts with the same overlap
        val a = "the quick brown fox"
        val b = "brown fox jumps over"
        assertEquals("the quick brown fox jumps over", SegmentMerge.merge(listOf(a, b)))
    }

    @Test fun joinsWithSpaceWhenNoSeam() {
        assertEquals("first part second part", SegmentMerge.merge(listOf("first part", "second part")))
    }

    @Test fun mergesChineseOverlap() {
        val a = "今天天氣很好我們去公園"
        val b = "我們去公園散步順便買咖啡"
        assertEquals("今天天氣很好我們去公園散步順便買咖啡", SegmentMerge.merge(listOf(a, b)))
    }

    @Test fun noSpaceWedgedBetweenChineseWindows() {
        // Whisper transcribed the overlap differently, so no seam is found; the boundary is between
        // two Han characters and must NOT get a wedged space.
        val a = "今天天氣很好"
        val b = "我們去公園散步"
        assertEquals("今天天氣很好我們去公園散步", SegmentMerge.merge(listOf(a, b)))
    }

    @Test fun mergesShortChineseOverlap() {
        // A 2-character Chinese overlap ("公園") is meaningful even though it is below the Latin floor.
        val a = "我們去公園"
        val b = "公園很大"
        assertEquals("我們去公園很大", SegmentMerge.merge(listOf(a, b)))
    }

    @Test fun keepsSpaceAtLatinBoundary() {
        assertEquals("看 Android", SegmentMerge.merge(listOf("看", "Android")))
    }

    @Test fun mergesThreeWindows() {
        val parts = listOf(
            "alpha beta gamma",
            "gamma delta epsilon",
            "epsilon zeta eta",
        )
        assertEquals("alpha beta gamma delta epsilon zeta eta", SegmentMerge.merge(parts))
    }
}
