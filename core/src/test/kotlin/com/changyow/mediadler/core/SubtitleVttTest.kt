package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.SubtitleVtt
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleVttTest {
    @Test fun stripsHeaderTimestampsAndTags() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: en

            1
            00:00:01.000 --> 00:00:03.000
            Hello <c>everyone</c>

            2
            00:00:03.000 --> 00:00:05.000
            welcome to the show
        """.trimIndent()
        assertEquals("Hello everyone\nwelcome to the show", SubtitleVtt.toPlainText(vtt))
    }

    @Test fun collapsesConsecutiveDuplicateRollingLines() {
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            今天天氣很好

            00:00:02.000 --> 00:00:04.000
            今天天氣很好

            00:00:04.000 --> 00:00:06.000
            我們去公園
        """.trimIndent()
        assertEquals("今天天氣很好\n我們去公園", SubtitleVtt.toPlainText(vtt))
    }

    @Test fun stripsInlineTimestampTags() {
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            hello<00:00:01.000><c> world</c>
        """.trimIndent()
        assertEquals("hello world", SubtitleVtt.toPlainText(vtt))
    }

    @Test fun ignoresNoteBlocks() {
        val vtt = """
            WEBVTT

            NOTE this is a comment

            00:00:00.000 --> 00:00:02.000
            real line
        """.trimIndent()
        assertEquals("real line", SubtitleVtt.toPlainText(vtt))
    }

    @Test fun emptyInputYieldsEmpty() {
        assertEquals("", SubtitleVtt.toPlainText("WEBVTT\n\n"))
    }
}
