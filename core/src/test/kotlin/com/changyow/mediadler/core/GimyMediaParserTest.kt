package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.DownloadPlanner
import com.changyow.mediadler.core.extract.GimyMediaParser
import com.changyow.mediadler.core.extract.GimyUrl
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GimyMediaParserTest {
    private val page = "https://gimytv.io/eps/202561679-2.html"
    private val frame = "https://gimytv.io/_watch/2037949"
    // Reduced public page structure sampled from the actual episode; no expiring CDN address.
    private val html = """<title>人來寶 Man Vs Baby 第02集 - Gimy TV 劇迷線上看</title>
        <h1 class="hidden-xs-"><a href="/voddetail2/202561679.html">人來寶線上看</a> - 第02集</h1>
        <iframe src="https://ads.example/video.mp4"></iframe>
        <iframe name="p-frame" src="/_watch/2037949" allowfullscreen></iframe>"""
    private fun player(urlLiteral: String = "'https://cdn.example/video/index.m3u8?token=a+b'") = """<script>
        var url = $urlLiteral;
        var type = 'hls';
        window.dp2 = new DPlayer({container: document.getElementById('dplayer'), video: {url: url, type: type}});
        </script>"""

    @Test fun observedPageAndIframeResolveWithHeadersAndStableIdentity() {
        assertEquals(frame, GimyMediaParser.playerUrl(html, page))
        val original = "https://gimytw.cc/eps/202561679-2.html"
        val item = GimyMediaParser.parse(html, player(), page, frame, original)
        assertEquals(SourceContext(original, page, SourceKind.GIMY), item.source)
        assertEquals("人來寶", item.seriesTitle)
        assertEquals(2, item.episodeNumber)
        assertNull(item.seasonNumber)
        assertEquals("人來寶 Man Vs Baby 第02集 - Gimy TV 劇迷線上看", item.originalTitle)
        assertEquals("https://cdn.example/video/index.m3u8?token=a+b", item.sourceUrl)
        val plan = DownloadPlanner.plan(DownloadRequest(item, FormatSelection.BestVideo))
        assertEquals(frame, plan.resource.httpHeaders["Referer"])
        assertEquals(GimyMediaParser.USER_AGENT, plan.resource.httpHeaders["User-Agent"])
    }

    @Test fun unrelatedAndAmbiguousIframesCannotBecomeThePlayer() {
        for (content in listOf("<iframe src='/_watch/1'></iframe>", html + "<iframe name='p-frame' src='/_watch/2'></iframe>",
            html.replace("/_watch/2037949", "https://advertising.example/_watch/2037949"))) {
            assertTrue(assertFailsWith<IllegalStateException> { GimyMediaParser.playerUrl(content, page) }.message.orEmpty().contains("播放器"))
        }
    }

    @Test fun jsStringEscapesDecodeWithoutExecutingJavascript() {
        val content = player("""'https:\/\/cdn.example\/it\'s\u0020video.mp4?x=\x2B'""")
        // A literal space is not a valid URI, so reject it instead of silently changing the resource.
        assertFailsWith<IllegalStateException> { GimyMediaParser.parse(html, content, page, frame) }
        val escaped = player("""'https:\/\/cdn.example\/video.mp4?x=\x2B\u002B'""")
        assertEquals("https://cdn.example/video.mp4?x=++", GimyMediaParser.parse(html, escaped, page, frame).sourceUrl)
        assertEquals("https://cdn.example/video.mp4", GimyMediaParser.parse(html, player("\"https://cdn.example/video.mp4\""), page, frame).sourceUrl)
    }

    @Test fun noPlayerOrMultipleResourcesFailClearly() {
        for (content in listOf("<script>var url='https://ads.example/a.m3u8';</script>", player() + player(),
            player().replace("var url =", "var url = decode("), "<div>網路錯誤</div>")) {
            assertTrue(assertFailsWith<IllegalStateException> { GimyMediaParser.parse(html, content, page, frame) }.message.orEmpty().contains("影片網址"))
        }
    }

    @Test fun unsupportedTargetsAndCommandsNeverReachTheDownloader() {
        for (url in listOf("file:///private/video.mp4", "javascript:alert(1)", "https://user:password@cdn.example/a.mp4",
            "--exec=bad", "https://player.example/watch/1")) {
            assertTrue(assertFailsWith<IllegalStateException> { GimyMediaParser.parse(html, player("'$url'"), page, frame) }
                .message.orEmpty().contains("HTTP"))
        }
    }

    @Test fun missingMetadataStaysUnknownInsteadOfUsingPlaybackIndexes() {
        val item = GimyMediaParser.parse("", player(), page, frame)
        assertEquals("Gimy_202561679", item.title)
        assertNull(item.originalTitle)
        assertNull(item.seriesTitle)
        assertNull(item.episodeNumber)
        assertNull(item.seasonNumber)
    }

    @Test fun entitiesInSeriesArePreservedAndUnknownEpisodeLabelsStayUnknown() {
        val content = html.replace("人來寶線上看", "A &amp; B線上看").replace("第02集", "預告片")
        val item = GimyMediaParser.parse(content, player(), page, frame)
        assertEquals("A & B", item.seriesTitle)
        assertNull(item.episodeNumber)
    }

    @Test fun knownHostsSupportAliasRedirectsButNotLookalikesOrNonPlaybackPages() {
        for (url in listOf(page, "https://gimytw.cc/eps/202561679-2.html", "https://WWW.GIMYTV.IO/eps/202561679-yu-gao-pian.html?share=1")) {
            assertTrue(GimyUrl.isSupported(url)); assertEquals("202561679", GimyUrl.playbackId(url))
        }
        for (url in listOf("https://gimytv.io.evil.example/eps/1-1.html", "https://gimytv.io:8443/eps/1-1.html", "https://user@gimytv.io/eps/1-1.html")) {
            assertFalse(GimyUrl.isSupported(url))
        }
        assertNull(GimyUrl.playbackId("https://gimytv.io/voddetail2/202561679.html"))
        assertTrue(GimyUrl.isPlayer(frame))
        assertFalse(GimyUrl.isPlayer("https://cdn.example/_watch/2037949"))
    }
}
