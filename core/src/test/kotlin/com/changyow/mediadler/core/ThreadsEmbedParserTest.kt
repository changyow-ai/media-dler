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

    /** Two post blocks for a tiny helper that wraps media in `OuterContainer` blocks. */
    private fun block(classes: String, handleHref: String, code: String?, mediaTag: String) = """
        <div class="$classes">
          <div class="HeaderContainer"><a href="$handleHref">name</a></div>
          ${code?.let { """<a href="https://www.threads.com/@x/post/$it?xmt=AQ">link</a>""" } ?: ""}
          <div class="MediaScrollImageContainer">$mediaTag</div>
        </div>
    """.trimIndent()

    private fun vid(name: String) =
        """<video><source src="https://instagram.fxx-1.fna.fbcdn.net/o1/v/t16/$name.mp4?nc=1"></video>"""

    /**
     * Multiple post blocks, NONE marked OuterContainerFull and none matching the shared post's
     * code or handle: the parser must refuse to guess and return nothing, NOT the parent's media.
     * Regression for the whole-page fallback re-introducing the over-capture bug.
     */
    @Test fun multiBlockWithNoIdentifiableSharedPostReturnsEmpty() {
        val shared = "https://www.threads.com/@nobodyhere/post/MISSING1"
        val html = "<div class=\"EmbedContainer\">" +
            block("OuterContainer", "https://www.threads.com/@liveistalking?x=1", code = null, mediaTag = vid("PARENT_VID")) +
            block("OuterContainer", "https://www.threads.com/@another?x=2", code = null, mediaTag = vid("OTHER_VID")) +
            "</div>"
        val items = ThreadsEmbedParser.parse(html, shared)
        assertEquals(0, items.size)
    }

    /** No OuterContainerFull, but exactly one block carries the shared post's /post/<code>: pick it. */
    @Test fun multiBlockSelectsByPostCodeWhenNoFullMarker() {
        val shared = "https://www.threads.com/@yatesvacuum/post/DZP1VHoD3rm"
        val html = "<div class=\"EmbedContainer\">" +
            block("OuterContainer", "https://www.threads.com/@liveistalking?x=1", code = null, mediaTag = vid("PARENT_VID")) +
            block("OuterContainer", "https://www.threads.com/@yatesvacuum?x=2", code = "DZP1VHoD3rm", mediaTag = vid("REPLY_VID")) +
            "</div>"
        val items = ThreadsEmbedParser.parse(html, shared)
        assertEquals(1, items.size)
        assertTrue(items[0].sourceUrl.contains("REPLY_VID.mp4"))
        assertTrue(items.none { it.sourceUrl.contains("PARENT_") })
    }

    /** No Full marker and no in-block code: the entity-encoded (&#064;) author handle disambiguates. */
    @Test fun multiBlockSelectsByEntityEncodedHandle() {
        val shared = "https://www.threads.com/@yatesvacuum/post/CODE9"
        val html = "<div class=\"EmbedContainer\">" +
            block("OuterContainer", "https://www.threads.com/&#064;liveistalking?x=1", code = null, mediaTag = vid("PARENT_VID")) +
            block("OuterContainer", "https://www.threads.com/&#064;yatesvacuum?x=2", code = null, mediaTag = vid("REPLY_VID")) +
            "</div>"
        val items = ThreadsEmbedParser.parse(html, shared)
        assertEquals(1, items.size)
        assertTrue(items[0].sourceUrl.contains("REPLY_VID.mp4"))
        assertTrue(items.none { it.sourceUrl.contains("PARENT_") })
    }

    /** Same author on two blocks (self-thread) with no Full marker is ambiguous → return nothing. */
    @Test fun multiBlockAmbiguousHandleReturnsEmpty() {
        val shared = "https://www.threads.com/@yatesvacuum/post/CODE9"
        val html = "<div class=\"EmbedContainer\">" +
            block("OuterContainer", "https://www.threads.com/@yatesvacuum?x=1", code = null, mediaTag = vid("PARENT_VID")) +
            block("OuterContainer", "https://www.threads.com/@yatesvacuum?x=2", code = null, mediaTag = vid("REPLY_VID")) +
            "</div>"
        val items = ThreadsEmbedParser.parse(html, shared)
        assertEquals(0, items.size)
    }

    /**
     * A post whose only visual is a link-attachment preview card (e.g. sharing an Instagram
     * link): `LinkAttachmentOuterContainer` merely *contains* "OuterContainer" and must NOT be
     * treated as a new post block, otherwise the preview image is cut out of the shared post's
     * scope and a visibly-media-bearing post reports "no media". Regression for
     * @xavier51090123/post/DakxbbCgeFW.
     */
    @Test fun linkAttachmentPreviewStaysInsideSharedPostBlock() {
        val shared = "https://www.threads.com/@xavier51090123/post/DakxbbCgeFW"
        val html = """
            <div class="OuterContainer OuterContainerFull">
              <div class="AvatarContainer"><img src="https://scontent.cdninstagram.com/v/t51.82787-19/AV.jpg?x=1"/></div>
              <div class="HeaderContainer"><a href="https://www.threads.com/@xavier51090123?xmt=AQ">xavier</a></div>
              <div class="LinkAttachmentOuterContainer LinkAttachmentOuterContainerWithImage" id="u_0_2_x0">
                <div class="LinkAttachmentInnerContainer">
                  <div class="LinkAttachmentImage" style="background-image: url(https://scontent.cdninstagram.com/v/t51.82787-15/PREVIEW.jpg?stp=dst-jpg_e35&amp;oh=9)"></div>
                </div>
              </div>
            </div>
        """.trimIndent()
        val items = ThreadsEmbedParser.parse(html, shared)
        assertEquals(1, items.size)
        assertTrue(items[0].isImage)
        assertTrue(items[0].sourceUrl.contains("PREVIEW.jpg"))
        assertTrue(items[0].sourceUrl.contains("oh=9"))
    }

    /** fbcdn is now matched for videos, but static.*.fbcdn.net sprites/emoji must NOT become images. */
    @Test fun fbcdnStaticSpritesAreNotExtractedAsImages() {
        val shared = "https://www.threads.com/@u/post/ABC"
        val html = block(
            "OuterContainer OuterContainerFull",
            "https://www.threads.com/@u?x=1",
            code = "ABC",
            mediaTag = vid("REAL_VID") +
                """<img src="https://static.xz.fbcdn.net/rsrc.php/v3/sprite.png?ccb=1"/>""" +
                """<img src="https://scontent.cdninstagram.com/v/t51.82787-15/REAL_IMG.jpg?y=2"/>""",
        )
        val items = ThreadsEmbedParser.parse(html, shared)
        assertEquals(2, items.size)
        assertTrue(items.any { it.sourceUrl.contains("REAL_VID.mp4") })
        assertTrue(items.any { it.sourceUrl.contains("REAL_IMG.jpg") })
        assertTrue(items.none { it.sourceUrl.contains("sprite.png") })
    }
}
