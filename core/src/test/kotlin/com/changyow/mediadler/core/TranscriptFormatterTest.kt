package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.TranscriptFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptFormatterTest {

    @Test fun breaksAfterChineseSentenceTerminals() {
        assertEquals(
            "今天天氣很好。\n我們出去走走吧！",
            TranscriptFormatter.format("今天天氣很好。我們出去走走吧！"),
        )
    }

    @Test fun keepsClosingQuoteWithSentence() {
        assertEquals(
            "他說「我來了」。\n然後就走了。",
            TranscriptFormatter.format("他說「我來了」。然後就走了。"),
        )
    }

    @Test fun groupsRepeatedTerminals() {
        assertEquals(
            "真的嗎？！\n太好了。",
            TranscriptFormatter.format("真的嗎？！太好了。"),
        )
    }

    @Test fun doesNotBreakDecimalsOrVersionsOrUrls() {
        // ASCII '.' between digits / inside tokens must not split.
        assertEquals("圓周率約 3.14 而已", TranscriptFormatter.format("圓周率約 3.14 而已"))
        assertEquals("版本 v1.2.3 發佈", TranscriptFormatter.format("版本 v1.2.3 發佈"))
        assertEquals("到 example.com 看看", TranscriptFormatter.format("到 example.com 看看"))
    }

    @Test fun breaksEnglishSentencesOnPeriodSpace() {
        assertEquals(
            "Hello world.\nHow are you?",
            TranscriptFormatter.format("Hello world. How are you?"),
        )
    }

    @Test fun softWrapsLongUnpunctuatedRunPreferringCommas() {
        // No sentence terminals at all (typical whisper-zh output): should wrap, not stay one line,
        // and not invent any 。
        val raw = "這是一段很長的沒有句號的中文字幕內容它會一直延續下去然後又接著講了更多的東西" +
            "而且還沒有結束繼續講繼續講還是沒有標點符號真的很長"
        val out = TranscriptFormatter.format(raw)
        assertTrue(out.contains("\n"), "long run should be wrapped")
        assertTrue(out.lines().all { it.length <= 42 }, "no line should exceed soft wrap")
        assertTrue(!out.contains("。"), "must not fabricate a period")
        assertEquals(raw, out.replace("\n", ""), "wrapping must be lossless")
    }

    @Test fun emptyStaysEmpty() {
        assertEquals("", TranscriptFormatter.format(""))
        assertEquals("", TranscriptFormatter.format("   \n  "))
    }

    @Test fun collapsesWhitespaceAfterTerminal() {
        assertEquals("好。\n走吧。", TranscriptFormatter.format("好。   走吧。"))
    }
}
