package com.changyow.mediadler.core

import com.changyow.mediadler.core.extract.YtDlpInfoParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpInfoParserTest {
    @Test fun singleVideoDropsStoryboardAndKeepsRealFormats() {
        val json = """
        {"id":"abc","title":"Test Video","ext":"mp4","thumbnail":"http://t/x.jpg","duration":100,
         "webpage_url":"https://youtu.be/abc","formats":[
          {"format_id":"18","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":360,"filesize":1000},
          {"format_id":"137","ext":"mp4","vcodec":"avc1","acodec":"none","height":1080,"filesize":5000},
          {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a","filesize":800},
          {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none"}
         ]}
        """.trimIndent()
        val items = YtDlpInfoParser.parse(json, "https://youtu.be/abc")
        assertEquals(1, items.size)
        val item = items[0]
        assertFalse(item.isImage)
        assertEquals(null, item.playlistIndex)
        assertEquals("https://youtu.be/abc", item.sourceUrl)
        assertEquals("Test Video", item.title)
        assertEquals(3, item.formats.size)
        val v137 = item.formats.first { it.formatId == "137" }
        assertTrue(v137.hasVideo)
        assertFalse(v137.hasAudio)
        assertEquals(1080, v137.height)
        val a140 = item.formats.first { it.formatId == "140" }
        assertFalse(a140.hasVideo)
        assertTrue(a140.hasAudio)
    }

    @Test fun carouselYieldsMultipleItemsWithIndices() {
        val json = """
        {"_type":"playlist","id":"post1","title":"Carousel","entries":[
          {"id":"img1","title":"Pic 1","ext":"jpg","url":"http://img/1.jpg",
           "formats":[{"format_id":"0","ext":"jpg","vcodec":"none","acodec":"none","url":"http://img/1.jpg","height":1080}]},
          {"id":"vid2","title":"Clip 2","ext":"mp4",
           "formats":[{"format_id":"dash","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":720}]}
        ]}
        """.trimIndent()
        val items = YtDlpInfoParser.parse(json, "https://insta/p/post1")
        assertEquals(2, items.size)
        assertTrue(items[0].isImage)
        assertEquals(1, items[0].playlistIndex)
        assertEquals("https://insta/p/post1", items[0].sourceUrl)
        assertFalse(items[1].isImage)
        assertEquals(2, items[1].playlistIndex)
        assertEquals(720, items[1].formats.first().height)
    }

    @Test fun singleImagePost() {
        val json = """
        {"id":"imgpost","title":"Single Pic","ext":"jpg","webpage_url":"https://insta/p/x",
         "url":"http://img/x.jpg",
         "formats":[{"format_id":"0","ext":"jpg","vcodec":"none","acodec":"none","url":"http://img/x.jpg","height":1080}]}
        """.trimIndent()
        val items = YtDlpInfoParser.parse(json, "https://insta/p/x")
        assertEquals(1, items.size)
        assertTrue(items[0].isImage)
        assertEquals(null, items[0].playlistIndex)
        assertEquals("https://insta/p/x", items[0].sourceUrl)
    }

    @Test fun directUrlWithoutFormats() {
        val json = """{"id":"d","title":"Direct","ext":"mp4","url":"http://v/x.mp4"}"""
        val items = YtDlpInfoParser.parse(json, "http://v/x.mp4")
        assertEquals(1, items.size)
        assertFalse(items[0].isImage)
        assertEquals(1, items[0].formats.size)
    }

    @Test fun allNullEntriesYieldEmpty() {
        val json = """{"_type":"playlist","id":"p","entries":[null,null]}"""
        assertEquals(0, YtDlpInfoParser.parse(json, "https://x/p").size)
    }

    @Test fun nestedPlaylistContainerIsDropped() {
        val json = """{"_type":"playlist","entries":[{"_type":"playlist","entries":[{"id":"v"}]}]}"""
        assertEquals(0, YtDlpInfoParser.parse(json, "https://x/chan").size)
    }

    @Test fun imageUrlWithDottedQueryIsClassifiedAsImage() {
        val json = """{"id":"i","title":"P","url":"http://cdn/photo.jpg?v=1.0&x=a.b"}"""
        val items = YtDlpInfoParser.parse(json, "http://cdn/photo.jpg")
        assertEquals(1, items.size)
        assertTrue(items[0].isImage)
    }
}
