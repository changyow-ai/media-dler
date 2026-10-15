package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.DownloadPlanner
import com.changyow.mediadler.core.extract.EightMapleMediaParser
import com.changyow.mediadler.core.extract.EightMapleUrl
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EightMapleMediaParserTest {
    private val page = "https://play.8maple.st/play/OD4C-2-4.html"
    private val stream = "https://unrelated-cdn.example/2026/master.m3u8?token=runtime"
    // Minimal structure observed on the requested page; no expired production stream is bundled.
    private val player = Json.parseToJsonElement("""{
        "flag":"play","encrypt":0,"url":"$stream","id":"OD4C","sid":2,"nid":4,
        "vod_data":{"vod_name":"測試影集 {新篇}","vod_actor":"unused"}
    }""").jsonObject

    private fun html(
        data: JsonObject = player,
        title: String = "<title>測試影集 &amp; 新篇|第04集|楓林網</title>",
        extra: String = "",
    ) = """<html><head>$title
        <meta property="og:url" content="https://8maple.it/play/other-1-1.html">
        <meta content="https://images.example/cover.jpg?a=1&amp;b=2" property="og:image">
        </head><body><script>var ad_url="https://ads.example/not-the-video.mp4";</script>
        <script type="text/javascript">var player_aaaa=$data;</script>$extra</body></html>"""

    private fun fields(vararg values: Pair<String, JsonElement>) = JsonObject(player + values)

    @Test fun observedPlayerShapeResolvesOneVideoWithIdentityHeadersAndEpisode() {
        val original = "https://8maple.st/play/OD4C-2-4.html?share=1"
        val item = EightMapleMediaParser.parse(html(), page, original)
        assertEquals(SourceContext(original, page, SourceKind.EIGHT_MAPLE), item.source)
        assertEquals(DownloadTarget.DIRECT, item.downloadTarget)
        assertEquals(stream, item.sourceUrl)
        assertNull(item.playlistIndex)
        assertEquals("8maple_OD4C_2_4", item.id)
        assertEquals("測試影集 & 新篇|第04集|楓林網", item.originalTitle)
        assertEquals("測試影集 {新篇}", item.seriesTitle)
        assertEquals(4, item.episodeNumber)
        assertNull(item.seasonNumber)
        assertEquals("https://images.example/cover.jpg?a=1&b=2", item.thumbnailUrl)
        assertEquals(page, item.httpHeaders["Referer"])
        assertEquals(EightMapleMediaParser.USER_AGENT, item.httpHeaders["User-Agent"])
        val plan = DownloadPlanner.plan(DownloadRequest(item, FormatSelection.BestVideo))
        assertEquals(item.formats.single().resource, plan.resource)
        assertFalse(item.isImage)
    }

    @Test fun encodedUrlUsesJavaScriptUnescapeWithoutChangingPlusIntoSpace() {
        val encoded = "https%3A%2F%2Fcdn.example%2Fmaster.m3u8%3Ftoken%3Da+b%2Bc"
        val item = EightMapleMediaParser.parse(html(fields("encrypt" to JsonPrimitive(1), "url" to JsonPrimitive(encoded))), page)
        assertEquals("https://cdn.example/master.m3u8?token=a+b+c", item.sourceUrl)
    }

    @Test fun base64ThenUnescapeMatchesPlayerScriptAndAcceptsStringFlag() {
        val encoded = Base64.getEncoder().encodeToString("https%3A%2F%2Fcdn.example%2Fvideo.mp4%3Fx%3Da+b".toByteArray())
        val item = EightMapleMediaParser.parse(html(fields("encrypt" to JsonPrimitive("2"), "url" to JsonPrimitive(encoded))), page)
        assertEquals("https://cdn.example/video.mp4?x=a+b", item.sourceUrl)
    }

    @Test fun missingLabelsDoNotTurnPlayerLineOrIndexIntoEpisodeOrSeason() {
        val item = EightMapleMediaParser.parse(html(title = "<title>電影完整版</title>"), page)
        assertNull(item.episodeNumber)
        assertNull(item.seasonNumber)
        assertEquals("電影完整版", item.originalTitle)
    }

    @Test fun missingTitleAndSeriesStayUnknownWithUsableFallback() {
        val item = EightMapleMediaParser.parse(html(fields("vod_data" to JsonNull), title = ""), page)
        assertEquals("8maple_OD4C", item.title)
        assertNull(item.originalTitle)
        assertNull(item.seriesTitle)
        assertNull(item.seasonNumber)
        assertNull(item.episodeNumber)
    }

    @Test fun missingMalformedOrAmbiguousPlayerDataFailsClearly() {
        val pages = listOf("<title>no player</title>", "<script>var player_aaaa={bad};</script>",
            "<script>var player_aaaa={\"url\":</script>", html(extra = "<script>var player_aaaa=$player;</script>"))
        pages.forEach { content ->
            assertTrue(assertFailsWith<IllegalStateException> { EightMapleMediaParser.parse(content, page) }
                .message.orEmpty().contains("播放資料"))
        }
    }

    @Test fun mismatchedVideoLineAndIndexNeverResolveAnotherEpisode() {
        for ((key, value) in listOf("id" to JsonPrimitive("other"), "sid" to JsonPrimitive(1), "nid" to JsonPrimitive(5))) {
            assertTrue(assertFailsWith<IllegalStateException> { EightMapleMediaParser.parse(html(fields(key to value)), page) }
                .message.orEmpty().contains("不符"))
        }
    }

    @Test fun missingUrlUnsupportedEncodingAndBadBase64HaveActionableErrors() {
        val cases = listOf(fields("url" to JsonNull) to "影片網址",
            fields("encrypt" to JsonPrimitive(99)) to "編碼",
            fields("encrypt" to JsonPrimitive(2), "url" to JsonPrimitive("not-base64!")) to "編碼")
        cases.forEach { (data, expected) ->
            assertTrue(assertFailsWith<IllegalStateException> { EightMapleMediaParser.parse(html(data), page) }
                .message.orEmpty().contains(expected))
        }
    }

    @Test fun nonHttpAndCredentialUrlsAreRejectedInsteadOfBecomingNativeCommands() {
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "--exec=bad", "https://user:pass@cdn.example/video.mp4")) {
            assertTrue(assertFailsWith<IllegalStateException> {
                EightMapleMediaParser.parse(html(fields("url" to JsonPrimitive(url))), page)
            }.message.orEmpty().contains("HTTP"))
        }
    }

    @Test fun intermediaryPlayerPageIsNotMisreportedAsDirectMedia() {
        assertTrue(assertFailsWith<IllegalStateException> {
            EightMapleMediaParser.parse(html(fields("url" to JsonPrimitive("https://player.example/watch?id=1"))), page)
        }.message.orEmpty().contains("其他播放線路"))
    }

    @Test fun onlyKnownSiteHostsAndSinglePlaybackPagesAreAccepted() {
        for (url in listOf(page, "https://8maple.st/play/hweC-2-5.html", "https://WWW.8MAPLE.ST/play/OD4C-2-4.html?share=1#player")) {
            assertTrue(EightMapleUrl.isSupported(url))
            assertTrue(EightMapleUrl.playbackOrNull(url) != null)
        }
        for (url in listOf("https://8maple.st.evil.example/play/OD4C-2-4.html", "https://8maple.st@evil.example/play/OD4C-2-4.html",
            "https://play.8maple.st:8443/play/OD4C-2-4.html", "file:///play/OD4C-2-4.html")) {
            assertFalse(EightMapleUrl.isSupported(url))
        }
        assertTrue(EightMapleUrl.isSupported("https://8maple.st/"))
        assertNull(EightMapleUrl.playbackOrNull("https://8maple.st/"))
        assertNull(EightMapleUrl.playbackOrNull("https://play.8maple.st/play/OD4C-0-0.html"))
    }
}
