package com.changyow.mediadler.core

import com.changyow.mediadler.core.extract.ShopeeMediaParser
import com.changyow.mediadler.core.extract.ShopeeUrl
import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.SourceContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopeeMediaParserTest {
    private val product = ShopeeUrl.Product("123", "456")
    private val best = "https://down-tw.vod.susercontent.com/media/best.mp4?quality=1"
    private val fixture = """
        {
          "documentUrl":"https://shopee.tw/product/123/456", "documentMatches":true, "found":true,
          "shopId":"123", "itemId":"456", "gallerySourceCount":2, "galleryComplete":true,
          "imageHashes":["gallery-one","gallery_two"], "itemHasVideo":false, "videoSourceCount":1,
          "videos":[{"id":"video-one","durationSeconds":20,"thumbnail":"cover-not-gallery","formats":[
            {"url":"$best","width":720,"height":1280,"size":0,"codec":"","format":"1600008"},
            {"url":"https://mms.vod.susercontent.com/media/default.mp4","width":540,"height":960,"size":"","codec":""}
          ]}],
          "image":"unused-cover", "variants":[{"image":"variant-not-gallery"}],
          "recommendations":[{"images":["recommendation-not-gallery"]}]
        }
    """.trimIndent()
    private val base = Json.parseToJsonElement(fixture).jsonObject

    private fun snapshot(vararg fields: Pair<String, JsonElement>) = JsonObject(base + fields).toString()

    @Test fun completeNativeSnapshotWithEmptyCodecSelectsOneBestVideoAndOnlyGalleryImages() {
        val items = ShopeeMediaParser.parse(fixture, product)
        assertEquals(3, items.size)
        val video = items.first()
        assertFalse(video.isImage)
        assertEquals(best, video.sourceUrl)
        assertEquals(20L, video.durationSeconds)
        assertEquals(1280, video.formats.single().height)
        assertNull(video.formats.single().filesizeBytes)
        assertEquals("https://down-tw.img.susercontent.com/file/cover-not-gallery", video.thumbnailUrl)
        assertTrue(video.formats.single().hasAudio)
        assertTrue(video.formats.single().hasVideo)
        assertEquals(listOf("gallery-one", "gallery_two"), items.drop(1).map { it.sourceUrl.substringAfterLast('/') })
        assertTrue(items.drop(1).all { it.isImage && it.formats.single().ext == "jpg" && !it.formats.single().hasAudio && !it.formats.single().hasVideo })
        assertTrue(items.all { it.playlistIndex == null && it.formats.single().formatId == "best" })
        assertEquals(3, items.map { it.id }.distinct().size)
        assertTrue(items.all { it.source == SourceContext(product.canonicalUrl, product.canonicalUrl) })
        assertTrue(items.all { it.downloadTarget == DownloadTarget.DIRECT && it.formats.single().resource?.url == it.sourceUrl })
        assertEquals(items.map { it.title }, ShopeeMediaParser.parse(fixture, product).map { it.title })
    }

    @Test fun knownH264WinsOverHigherResolutionH265WithoutDuplicatingVideo() {
        val json = fixture.replaceFirst("\"codec\":\"\"", "\"codec\":\"H264\"")
            .replace("\"codec\":\"\"", "\"codec\":\"H265\"")
            .replace("\"width\":540,\"height\":960", "\"width\":2160,\"height\":3840")
        val items = ShopeeMediaParser.parse(json, product)
        assertEquals(1, items.count { !it.isImage })
        assertEquals(best, items.first().sourceUrl)
    }

    @Test fun missingExpectedVideoNeverReturnsImagesOnly() {
        assertTrue(ShopeeMediaParser.parse(snapshot("videos" to JsonArray(emptyList())), product).isEmpty())
        assertTrue(ShopeeMediaParser.parse(snapshot(
            "videos" to JsonArray(emptyList()), "videoSourceCount" to JsonPrimitive(0), "itemHasVideo" to JsonPrimitive(true),
        ), product).isEmpty())
        assertTrue(ShopeeMediaParser.parse(snapshot(
            "videos" to JsonArray(emptyList()), "videoSourceCount" to JsonPrimitive(0),
        ), product).all { it.isImage })
    }

    @Test fun wrongProductOrDocumentIdentityReturnsNothing() {
        assertTrue(ShopeeMediaParser.parse(snapshot("shopId" to JsonPrimitive("999")), product).isEmpty())
        assertTrue(ShopeeMediaParser.parse(snapshot("documentUrl" to JsonPrimitive("https://shopee.tw/product/123/999")), product).isEmpty())
        assertTrue(ShopeeMediaParser.parse(snapshot("documentMatches" to JsonPrimitive(false)), product).isEmpty())
    }

    @Test fun maliciousVideoFormatRejectsWholeSnapshotEvenWhenAnotherFormatIsValid() {
        listOf(
            "https://down-tw.vod.susercontent.com.evil.example/media/best.mp4",
            "https://user@down-tw.vod.susercontent.com/media/best.mp4",
            "https://down-tw.vod.susercontent.com:8443/media/best.mp4",
            "blob:https://down-tw.vod.susercontent.com/video",
        ).forEach { bad ->
            assertTrue(ShopeeMediaParser.parse(fixture.replace(best, bad), product).isEmpty(), bad)
        }
    }

    @Test fun incompleteInvalidOrOversizeGalleryReturnsNothing() {
        assertTrue(ShopeeMediaParser.parse(snapshot("gallerySourceCount" to JsonPrimitive(3)), product).isEmpty())
        assertTrue(ShopeeMediaParser.parse(snapshot("galleryComplete" to JsonPrimitive(false)), product).isEmpty())
        assertTrue(ShopeeMediaParser.parse(snapshot("imageHashes" to JsonArray(listOf(JsonPrimitive("https://example.com/image.jpg")))), product).isEmpty())
        val tooMany = JsonArray(List(51) { JsonPrimitive("gallery_$it") })
        assertTrue(ShopeeMediaParser.parse(snapshot("imageHashes" to tooMany, "gallerySourceCount" to JsonPrimitive(51)), product).isEmpty())
    }
}
