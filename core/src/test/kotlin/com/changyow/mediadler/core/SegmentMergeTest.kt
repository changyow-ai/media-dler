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

    @Test fun mergesThreeWindows() {
        val parts = listOf(
            "alpha beta gamma",
            "gamma delta epsilon",
            "epsilon zeta eta",
        )
        assertEquals("alpha beta gamma delta epsilon zeta eta", SegmentMerge.merge(parts))
    }
}
