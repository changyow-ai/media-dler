package com.changyow.mediadler.core

import com.changyow.mediadler.core.extract.ThreadsEmbedParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadsEmbedParserTest {
    private val post = "https://www.threads.com/@u/post/ABC123"

    @Test fun videoPostExtractsVideoNotAvatarAndKeepsQuery() {
        val html = """
            <video src="https://scontent-x.cdninstagram.com/o1/v/t16/f2/m84/AQ.mp4?efg=a&amp;oe=2"></video>
            <img class="img" src="https://scontent-x.cdninstagram.com/v/t51.82787-19/527_n.jpg?stp=dst-jpg_s100x100"/>
        """.trimIndent()
        val items = ThreadsEmbedParser.parse(html, post)
        assertEquals(1, items.size)
        assertFalse(items[0].isImage)
        assertTrue(items[0].sourceUrl.contains("AQ.mp4"))
        assertTrue(items[0].sourceUrl.contains("oe=2"))
        assertEquals("ThreadsVideo_ABC123", items[0].title)
    }

    @Test fun imageCoverExtractedAvatarAndStaticSkipped() {
        val html = """
            <img class="img" src="https://scontent-x.cdninstagram.com/v/t51.82787-19/514_n.jpg?x=1"/>
            <img src="https://static.cdninstagram.com/rsrc.php/y.webp"/>
            data="https://scontent-x.cdninstagram.com/v/t51.82787-15/522_n.jpg?stp=dst-jpg_e35&amp;oh=9"
        """.trimIndent()
        val items = ThreadsEmbedParser.parse(html, post)
        assertEquals(1, items.size)
        assertTrue(items[0].isImage)
        assertTrue(items[0].sourceUrl.contains("522_n.jpg"))
    }

    @Test fun videoPlusImageGivesTwo() {
        val html = """
            <video src="https://s.cdninstagram.com/o1/v/AQ.mp4?a=1"></video>
            <img src="https://s.cdninstagram.com/v/t51.1-15/77_n.jpg?b=2"/>
            <img src="https://s.cdninstagram.com/v/t51.1-19/av.jpg?c=3"/>
        """.trimIndent()
        val items = ThreadsEmbedParser.parse(html, post)
        assertEquals(2, items.size)
        assertFalse(items[0].isImage)
        assertTrue(items[1].isImage)
    }

    @Test fun textOnlyReturnsEmpty() {
        assertEquals(0, ThreadsEmbedParser.parse("<div>hello world</div>", post).size)
    }
}
