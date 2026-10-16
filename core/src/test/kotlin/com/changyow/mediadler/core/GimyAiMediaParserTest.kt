package com.changyow.mediadler.core

import com.changyow.mediadler.core.extract.GimyMediaParser
import com.changyow.mediadler.core.extract.GimyUrl
import com.changyow.mediadler.core.extract.SeriesCatalogParser
import com.changyow.mediadler.core.extract.SpecialSources
import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GimyAiMediaParserTest {
    private val page = "https://gimyai.tw/play/916639-1-1.html"
    // Reduced observed player_aaaa shape; CDN addresses are replaced with stable fixtures.
    private val html = """<title>Doctor X：白色黑手黨時代 第01集線上看 - Gimy 劇迷</title>
        <meta property="og:image" content="https://images.example/poster.webp">
        <script>var player_aaaa={"flag":"play","encrypt":0,
          "vod_data":{"vod_name":"Doctor X：白色黑手黨時代"},
          "url":"https:\/\/cdn.example\/current\/index.m3u8?token=a+b",
          "url_next":"https:\/\/cdn.example\/next\/index.m3u8",
          "from":"xiguam3u8","id":"916639","sid":1,"nid":1};var player_data=player_aaaa;</script>"""

    @Test fun observedStaticPlayerResolvesCurrentEpisodeWithGimyIdentityAndHeaders() {
        val original = "$page?share=original"
        val item = GimyMediaParser.parsePage(html, page, original)
        assertEquals("https://cdn.example/current/index.m3u8?token=a+b", item.sourceUrl)
        assertEquals("gimy_916639_1_1", item.id)
        assertEquals(SourceKind.GIMY, item.source.kind)
        assertEquals(original, item.source.originalUrl)
        assertEquals(page, item.source.pageUrl)
        assertEquals("Doctor X：白色黑手黨時代", item.seriesTitle)
        assertEquals(1, item.episodeNumber)
        assertEquals(DownloadTarget.DIRECT, item.downloadTarget)
        assertEquals(page, item.httpHeaders["Referer"])
        assertEquals(GimyMediaParser.USER_AGENT, item.httpHeaders["User-Agent"])
        assertEquals(item.sourceUrl, item.formats.single().resource?.url)
    }

    @Test fun newPlaybackPagesUseTheSpecialEpisodeQueueAndStrictUrlBoundaries() {
        assertEquals(SourceKind.GIMY, SpecialSources.episodePageOrNull(page)?.kind)
        assertEquals("916639", GimyUrl.playbackId(page))
        assertTrue(SeriesCatalogParser.isCatalogUrl("https://WWW.GIMYAI.TW:443/detail/916639.html?share=1"))
        for (url in listOf(
            "http://gimyai.tw/play/916639-1-1.html", "https://gimyai.tw.evil.example/play/916639-1-1.html",
            "https://user@gimyai.tw/play/916639-1-1.html", "https://gimyai.tw:8443/play/916639-1-1.html",
            "https://gimyai.tw/play/916639-0-1.html", "https://gimyai.tw/play/916639-1-0.html",
            "https://gimyai.tw/eps/916639-1.html", "https://gimytv.io/play/916639-1-1.html",
        )) assertNull(GimyUrl.playbackId(url), url)
        assertFalse(SeriesCatalogParser.isCatalogUrl("https://gimyai.tw/voddetail2/916639.html"))
        assertFalse(SeriesCatalogParser.isCatalogUrl("https://gimytv.io/detail/916639.html"))
        assertFalse(GimyUrl.isPlayer("https://gimyai.tw/_watch/123"))
    }

    @Test fun anotherSeriesRouteOrEpisodeCannotResolveAsTheRequestedPage() {
        for ((before, after) in listOf("\"916639\"" to "\"123456\"", "\"sid\":1" to "\"sid\":2", "\"nid\":1" to "\"nid\":2")) {
            val error = assertFailsWith<IllegalStateException> { GimyMediaParser.parsePage(html.replace(before, after), page) }
            assertTrue(error.message.orEmpty().contains("不符"))
        }
    }

    @Test fun opaquePlayerCodesAndAmbiguousDataFailWithoutChoosingAnotherRoute() {
        val opaque = html.replace("https:\\/\\/cdn.example\\/current\\/index.m3u8?token=a+b", "qsvip-opaque-player-code")
        val error = assertFailsWith<IllegalStateException> { GimyMediaParser.parsePage(opaque, page) }
        assertTrue(error.message.orEmpty().contains("其他播放線路"))
        assertFailsWith<IllegalStateException> { GimyMediaParser.parsePage(html + html, page) }
    }
}
