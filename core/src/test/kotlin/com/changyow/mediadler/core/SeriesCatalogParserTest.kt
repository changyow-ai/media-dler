package com.changyow.mediadler.core

import com.changyow.mediadler.core.extract.SeriesCatalogParser
import com.changyow.mediadler.core.model.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesCatalogParserTest {
    private val eightMapleUrl = "https://8maple.st/video/5uYC.html"
    private val gimyUrl = "https://gimytv.io/voddetail2/202561679.html"

    @Test fun actualEightMapleCatalogPreservesFourLinesAndAllEpisodeLabelsInPageOrder() {
        val original = "$eightMapleUrl?share=original"
        val catalog = SeriesCatalogParser.parse(fixture("8maple-5uYC.html"), eightMapleUrl, original)

        assertEquals(original, catalog.originalUrl)
        assertEquals(eightMapleUrl, catalog.pageUrl)
        assertEquals(SourceKind.EIGHT_MAPLE, catalog.kind)
        assertEquals("媽媽發瘋了", catalog.title)
        assertEquals(listOf("4", "3", "1", "2"), catalog.lines.map { it.id })
        assertEquals(listOf("速通線路", "愛看線路", "高光線路", "秒光線路"), catalog.lines.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), catalog.lines.map { it.sourceOrder })
        assertEquals(52, catalog.lines.sumOf { it.episodes.size })
        catalog.lines.forEach { line ->
            assertEquals(13, line.episodes.size)
            assertEquals((1..13).toList(), line.episodes.map { it.page.episodeNumber })
            assertEquals((0..12).toList(), line.episodes.map { it.sourceOrder })
            line.episodes.forEach { episode ->
                assertEquals(eightMapleUrl, episode.page.catalogUrl)
                assertEquals(line.id, episode.page.lineId)
                assertEquals(line.name, episode.page.lineName)
                assertEquals("媽媽發瘋了", episode.page.seriesTitle)
                assertNull(episode.page.seasonNumber)
                assertTrue(episode.page.playbackUrl.startsWith("https://play.8maple.st/play/5uYC-${line.id}-"))
                assertFalse(episode.page.playbackUrl.contains("m3u8"))
            }
        }
        assertEquals("第01集", catalog.lines.first().episodes.first().label)
        assertEquals("第1集", catalog.lines.last().episodes.first().label)
        assertEquals("https://play.8maple.st/play/5uYC-4-1.html", catalog.lines.first().episodes.first().page.playbackUrl)
        assertEquals("https://cdn02.kolvod.com/upload/vod/20260929-1/f3cfe658d1599c97705c84462a8942bc.jpg", catalog.thumbnailUrl)
    }

    @Test fun resolvesRelativeAndProtocolRelativeLinksAndUsesOnlyConfirmedEpisodeAndSeasonLabels() {
        val html = eightMaple(
            "4" to (link("/play/5uYC-4-99.html?x=1&amp;y=2", "第7話") +
                link("//play.8maple.st/play/5uYC-4-100.html", "特別篇")),
            title = "影集 第3季",
        )
        val episodes = SeriesCatalogParser.parse(html, eightMapleUrl).lines.single().episodes
        assertEquals(listOf("第7話", "特別篇"), episodes.map { it.label })
        assertEquals("https://8maple.st/play/5uYC-4-99.html?x=1&y=2", episodes.first().page.playbackUrl)
        assertEquals("https://play.8maple.st/play/5uYC-4-100.html", episodes.last().page.playbackUrl)
        assertEquals(listOf(7, null), episodes.map { it.page.episodeNumber })
        assertEquals(listOf(3, 3), episodes.map { it.page.seasonNumber })
        assertEquals(listOf("4", "4"), episodes.map { it.page.lineId })
    }

    @Test fun actualGimyCatalogHasOneDefaultLineAndRetainsDescendingSourceOrderAndUnknownTrailer() {
        val catalog = SeriesCatalogParser.parse(fixture("gimy-202561679.html"), gimyUrl)
        assertEquals(SourceKind.GIMY, catalog.kind)
        assertEquals("人來寶", catalog.title)
        assertEquals("https://gimytv.io/poster/202561679.jpg", catalog.thumbnailUrl)
        val line = catalog.lines.single()
        assertEquals("default", line.id)
        assertEquals("在線播放", line.name)
        assertEquals(0, line.sourceOrder)
        assertEquals(listOf("第04集", "第03集", "第02集", "第01集", "预告片"), line.episodes.map { it.label })
        assertEquals(listOf(4, 3, 2, 1, null), line.episodes.map { it.page.episodeNumber })
        assertEquals((0..4).toList(), line.episodes.map { it.sourceOrder })
        assertEquals("https://gimytv.io/eps/202561679-yu-gao-pian.html", line.episodes.last().page.playbackUrl)
        line.episodes.forEach {
            assertEquals(gimyUrl, it.page.catalogUrl)
            assertEquals("default", it.page.lineId)
            assertEquals("人來寶", it.page.seriesTitle)
            assertNull(it.page.seasonNumber)
            assertFalse(it.page.playbackUrl.contains("m3u8"))
        }
    }

    @Test fun ignoresNavigationAdsRecommendationsAndOtherShowsOrLinesEvenInsideEpisodeList() {
        val noise = link("/play/5uYC-4-90.html", "立即播放") +
            link("/play/5uYC-4-91.html", "推薦影片") +
            link("/play/5uYC-4-92.html", "廣告") +
            link("/play/OTHER-4-2.html", "第02集") +
            link("/play/5uYC-3-2.html", "第02集") +
            link("https://ads.example/play/5uYC-4-2.html", "第02集") +
            link("https://cdn.example/episode.m3u8", "第02集")
        val catalog = SeriesCatalogParser.parse(
            eightMaple("4" to (link("/play/5uYC-4-1.html", "第01集") + noise)) +
                link("/play/5uYC-4-3.html", "第03集"),
            eightMapleUrl,
        )
        assertEquals(listOf("第01集"), catalog.lines.single().episodes.map { it.label })

        val gimyNoise = link("/eps/202561679-90.html", "立即播放") +
            link("/eps/202561679-91.html", "推薦影片") +
            link("/eps/202561679-92.html", "廣告") +
            link("/eps/999999999-2.html", "第02集")
        val gimy = SeriesCatalogParser.parse(
            gimy(link("/eps/202561679-1.html", "第01集") + gimyNoise), gimyUrl,
        )
        assertEquals(listOf("第01集"), gimy.lines.single().episodes.map { it.label })
    }

    @Test fun repeatedEntryForSamePlaybackPageDoesNotDuplicateEpisode() {
        val html = eightMaple("4" to (
            link("/play/5uYC-4-1.html?share=first", "第01集") +
                link("//play.8maple.st/play/5uYC-4-1.html?share=again", "第1集")
            ))
        val episodes = SeriesCatalogParser.parse(html, eightMapleUrl).lines.single().episodes
        assertEquals(1, episodes.size)
        assertEquals("第01集", episodes.single().label)
        assertEquals(1, episodes.single().page.episodeNumber)
        assertEquals("https://8maple.st/play/5uYC-4-1.html?share=first", episodes.single().page.playbackUrl)
    }

    @Test fun conflictingPlaybackUrlsForSameEpisodeFailWithUnderstandableMessage() {
        val html = eightMaple("4" to (
            link("/play/5uYC-4-1.html", "第01集") + link("/play/5uYC-4-99.html", "第1集")
            ))
        val error = assertFailsWith<IllegalStateException> { SeriesCatalogParser.parse(html, eightMapleUrl) }
        assertTrue(error.message.orEmpty().contains("重複集數"))
    }

    @Test fun missingTabsMissingListAndDuplicateDomIdsFailClearly() {
        val body = link("/play/5uYC-4-1.html", "第01集")
        val cases = listOf(
            block("4", body),
            "<a href='#playlist4' data-toggle='tab'>線路 4</a>",
            eightMaple("4" to body) + block("4", body),
        )
        cases.forEach { html ->
            val error = assertFailsWith<IllegalStateException> { SeriesCatalogParser.parse(html, eightMapleUrl) }
            assertTrue(error.message.orEmpty().contains("8maple"))
            assertTrue(error.message.orEmpty().contains("缺失") || error.message.orEmpty().contains("不明確"))
        }
    }

    @Test fun retainsEmptyLineButRejectsCatalogWithNoSelectableEpisodes() {
        val catalog = SeriesCatalogParser.parse(
            eightMaple("4" to "", "3" to link("/play/5uYC-3-1.html", "第01集")), eightMapleUrl,
        )
        assertEquals(listOf("4", "3"), catalog.lines.map { it.id })
        assertTrue(catalog.lines.first().episodes.isEmpty())
        assertEquals(1, catalog.lines.last().episodes.size)
        val error = assertFailsWith<IllegalStateException> {
            SeriesCatalogParser.parse(eightMaple("4" to "", "3" to ""), eightMapleUrl)
        }
        assertTrue(error.message.orEmpty().contains("沒有可選集數"))
    }

    @Test fun absentHeadingLeavesSeriesAndSeasonUnknownRatherThanGuessingFromPageTitleOrLine() {
        val html = "<title>看似影集 第8季 - 第99集</title>" + eightMaple(
            "4" to link("/play/5uYC-4-99.html", "特別篇"), title = null,
        )
        val catalog = SeriesCatalogParser.parse(html, eightMapleUrl)
        assertTrue(catalog.title.contains("5uYC"))
        val page = catalog.lines.single().episodes.single().page
        assertNull(page.seriesTitle)
        assertNull(page.seasonNumber)
        assertNull(page.episodeNumber)
        assertEquals("4", page.lineId)
    }

    @Test fun commentedAndScriptedFakePlaylistsDoNotChangeParsedCatalog() {
        val html = fixture("8maple-5uYC.html")
        val expected = SeriesCatalogParser.parse(html, eightMapleUrl)
        val fake = "<h1>假的 第99季</h1>" + eightMaple("4" to link("/play/5uYC-4-999.html", "第999集"))
        val actual = SeriesCatalogParser.parse(
            "<!--$fake--><script>const fake = `$fake`;</script><style>/*$fake*/</style>$html", eightMapleUrl,
        )
        assertEquals(expected, actual)
    }

    @Test fun unobservedGimyMultipleListsFailInsteadOfGuessingIframeLineNumbers() {
        val html = gimy(link("/eps/202561679-1.html", "第01集")) +
            "<div class='playlist'><ul id='con_playlist_2'>${link("/eps/202561679-2.html", "第02集")}</ul></div>"
        val error = assertFailsWith<IllegalStateException> { SeriesCatalogParser.parse(html, gimyUrl) }
        assertTrue(error.message.orEmpty().contains("Gimy"))
        assertTrue(error.message.orEmpty().contains("線路結構已變更"))
        assertTrue(error.message.orEmpty().contains("單集播放頁"))
    }

    @Test fun catalogGuardAcceptsOnlyKnownHttpsCatalogPages() {
        val accepted = listOf(
            eightMapleUrl, "https://www.8maple.st/video/5uYC.html?share=a#episodes",
            "https://play.8maple.st:443/video/5uYC.html", gimyUrl,
            "https://www.gimytw.cc/voddetail2/202561679.html?share=a",
        )
        accepted.forEach { assertTrue(SeriesCatalogParser.isCatalogUrl(it), it) }
        val rejected = listOf(
            "http://8maple.st/video/5uYC.html", "https://8maple.st.evil.example/video/5uYC.html",
            "https://8maple.st@evil.example/video/5uYC.html", "https://user@8maple.st/video/5uYC.html",
            "https://8maple.st:8443/video/5uYC.html", "https://8maple.st/play/5uYC-4-1.html",
            "https://8maple.st/video/5uYC.html/extra", "https://8maple.it/video/5uYC.html",
            "https://gimytv.io.evil.example/voddetail2/202561679.html", "https://user@gimytv.io/voddetail2/202561679.html",
            "https://gimytv.io:8443/voddetail2/202561679.html", "https://gimytv.io/eps/202561679-1.html",
            "https://gimytv.io/_watch/123", "https://gimytv.io/voddetail2/not-a-number.html", "not a URL",
        )
        rejected.forEach { assertFalse(SeriesCatalogParser.isCatalogUrl(it), it) }
        assertFailsWith<IllegalStateException> {
            SeriesCatalogParser.parse(fixture("8maple-5uYC.html"), "https://evil.example/video/5uYC.html")
        }
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResourceAsStream("/catalog/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun link(url: String, label: String) = "<li><a href='$url'>$label</a></li>"

    private fun block(id: String, body: String) =
        "<div id='playlist$id'><ul class='myui-content__list sort-list'>$body</ul></div>"

    private fun eightMaple(vararg lines: Pair<String, String>, title: String? = "範例影集"): String = buildString {
        title?.let { append("<h1>$it</h1>") }
        append("<ul class='nav nav-tabs'>")
        lines.forEach { (id, _) -> append("<li><a href='#playlist$id' data-toggle='tab'>線路 $id</a></li>") }
        append("</ul>")
        lines.forEach { (id, body) -> append(block(id, body)) }
    }

    private fun gimy(body: String) =
        "<h1>人來寶</h1><div class='playlist'><ul id='con_playlist_1'>$body</ul></div>"
}
