package com.changyow.mediadler.core

import com.changyow.mediadler.core.extract.YtDlpInfoParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpInfoParserTest {
    @Test fun instagramPhotoWithoutVideoFormatsUsesOriginalImageUrl() {
        // Shape returned for DeRkSRKDnnJ with --ignore-no-formats-error.
        val json = """
        {"id":"DeRkSRKDnnJ","title":"Video by imleaderkid","_type":"video",
         "extractor_key":"Instagram","formats":[],
         "webpage_url":"https://www.instagram.com/p/DeRkSRKDnnJ/",
         "thumbnail":"https://cdn.example/original.jpg?stp=dst-jpg_e35&x=1.0",
         "thumbnails":[{"url":"https://cdn.example/cropped.jpg"},
                       {"url":"https://cdn.example/original.jpg?stp=dst-jpg_e35&x=1.0"}]}
        """.trimIndent()

        val items = YtDlpInfoParser.parse(json, "https://www.instagram.com/p/DeRkSRKDnnJ/")

        assertEquals(1, items.size)
        val image = items.single()
        assertTrue(image.isImage)
        assertEquals("https://cdn.example/original.jpg?stp=dst-jpg_e35&x=1.0", image.sourceUrl)
        assertEquals(null, image.playlistIndex)
        assertEquals("jpg", image.formats.single().ext)
        assertFalse(image.formats.single().hasVideo)
        assertFalse(image.formats.single().hasAudio)
    }

    @Test fun instagramMixedCarouselKeepsImagesAndVideoPlaylistIndices() {
        val post = "https://www.instagram.com/p/mixed/"
        val json = """
        {"_type":"playlist","extractor_key":"Instagram","entries":[
          {"id":"photo1","formats":[],"thumbnail":"https://cdn.example/photo1.jpg"},
          {"id":"video2","duration":12,"thumbnail":"https://cdn.example/video-preview.jpg",
           "formats":[{"format_id":"dash","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":720}]},
          {"id":"photo3","formats":[],"thumbnail":"https://cdn.example/photo3.webp"}
        ]}
        """.trimIndent()

        val items = YtDlpInfoParser.parse(json, post)

        assertEquals(listOf("photo1", "video2", "photo3"), items.map { it.id })
        assertEquals(listOf(true, false, true), items.map { it.isImage })
        assertEquals(listOf(null, 2, null), items.map { it.playlistIndex })
        assertEquals(listOf("https://cdn.example/photo1.jpg", post, "https://cdn.example/photo3.webp"),
            items.map { it.sourceUrl })
        assertEquals("dash", items[1].formats.single().formatId)
        assertEquals(720, items[1].formats.single().height)
    }

    @Test fun instagramReelKeepsVideoAndAudioFormatsInsteadOfThumbnail() {
        val post = "https://www.instagram.com/reel/DbV0B5SNN5V/"
        val json = """
        {"id":"DbV0B5SNN5V","extractor_key":"Instagram","webpage_url":"$post",
         "thumbnail":"https://cdn.example/preview.jpg","duration":15,
         "formats":[
           {"format_id":"video","ext":"mp4","vcodec":"avc1","acodec":"none","height":1280},
           {"format_id":"audio","ext":"m4a","vcodec":"none","acodec":"mp4a"}
         ]}
        """.trimIndent()

        val video = YtDlpInfoParser.parse(json, post).single()

        assertFalse(video.isImage)
        assertEquals(post, video.sourceUrl)
        assertEquals(null, video.playlistIndex)
        assertEquals(listOf("video", "audio"), video.formats.map { it.formatId })
    }

    @Test fun unavailableInstagramVideoDoesNotBecomeItsPreviewImage() {
        val json = """
        {"id":"video","extractor_key":"Instagram","duration":15,"ext":"mp4",
         "formats":[],"thumbnail":"https://cdn.example/preview.jpg"}
        """.trimIndent()

        assertEquals(emptyList(), YtDlpInfoParser.parse(json, "https://www.instagram.com/p/video/"))
    }

    @Test fun otherExtractorsDoNotTreatThumbnailsAsImagePosts() {
        val json = """
        {"id":"abc","extractor_key":"Youtube","formats":[],
         "thumbnail":"https://cdn.example/preview.jpg"}
        """.trimIndent()

        assertEquals(emptyList(), YtDlpInfoParser.parse(json, "https://youtu.be/abc"))
    }

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
