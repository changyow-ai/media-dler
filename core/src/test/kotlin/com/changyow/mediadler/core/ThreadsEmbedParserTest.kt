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

    /**
     * A reply embed renders the parent post as context above the shared reply (marked
     * `OuterContainerFull`). Only the reply's media — including its fbcdn-hosted video — must be
     * returned; the parent's media must be ignored. Regression for sharing a comment grabbing the
     * main post's video.
     */
    @Test fun replyEmbedExtractsOnlySharedPostMediaIncludingFbcdnVideo() {
        val reply = "https://www.threads.com/@yatesvacuum/post/DZP1VHoD3rm"
        val html = """
            <div class="LinkContainer"><a href="https://www.threads.com/@yatesvacuum/post/DZP1VHoD3rm?xmt=AQ">open</a></div>
            <div class="EmbedContainer">
              <div class="OuterContainer">
                <div class="AvatarContainer"><img src="https://scontent.cdninstagram.com/v/t51.82787-19/AV_PARENT.jpg?x=1"/></div>
                <div class="HeaderContainer"><a href="https://www.threads.com/@liveistalking?xmt=AQ">liveistalking</a></div>
                <div class="MediaScrollImageContainer"><video><source src="https://instagram.fxx-1.fna.fbcdn.net/o1/v/t16/PARENT_VID.mp4?nc=1&amp;oe=9"></video></div>
                <div class="MediaScrollImageContainer"><img src="https://scontent.cdninstagram.com/v/t51.82787-15/PARENT_IMG.jpg?y=2"/></div>
              </div>
              <div class="OuterContainer OuterContainerFull">
                <div class="AvatarContainer"><img src="https://scontent.cdninstagram.com/v/t51.82787-19/AV_REPLY.jpg?x=3"/></div>
                <div class="HeaderContainer"><a href="https://www.threads.com/@yatesvacuum?xmt=AQ">yatesvacuum</a></div>
                <div class="MediaScrollImageContainer"><video><source src="https://instagram.fxx-1.fna.fbcdn.net/o1/v/t16/REPLY_VID.mp4?nc=2&amp;oe=8"></video></div>
                <div class="MediaScrollImageContainer"><img src="https://scontent.cdninstagram.com/v/t51.82787-15/REPLY_IMG.jpg?y=4"/></div>
              </div>
            </div>
        """.trimIndent()
        val items = ThreadsEmbedParser.parse(html, reply)
        assertEquals(2, items.size)
        assertFalse(items[0].isImage)
        assertTrue(items[0].sourceUrl.contains("REPLY_VID.mp4"))
        assertTrue(items[0].sourceUrl.contains("fbcdn.net"))
        assertEquals("ThreadsVideo_DZP1VHoD3rm", items[0].title)
        assertTrue(items[1].isImage)
        assertTrue(items[1].sourceUrl.contains("REPLY_IMG.jpg"))
        assertTrue(items.none { it.sourceUrl.contains("PARENT_") })
    }
}
