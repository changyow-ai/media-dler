package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.EpisodeSelectionPolicy
import com.changyow.mediadler.core.download.EpisodeSelectionPolicy.Selection
import com.changyow.mediadler.core.model.CatalogEpisode
import com.changyow.mediadler.core.model.EpisodePage
import com.changyow.mediadler.core.model.PlaybackLine
import com.changyow.mediadler.core.model.SeriesCatalog
import com.changyow.mediadler.core.model.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeSelectionPolicyTest {
    private fun episode(line: String, number: Int?, order: Int, label: String = "第${number}集") =
        CatalogEpisode(label, EpisodePage("https://play.8maple.st/play/show-$line-$order.html",
            SourceKind.EIGHT_MAPLE, episodeNumber = number, seriesTitle = "劇名"), order)
    private val a = PlaybackLine("A", "速通線路", 0, listOf(episode("1", 1, 0), episode("1", 2, 1)))
    private val b = PlaybackLine("B", "愛看線路", 1, listOf(episode("2", 1, 0), episode("2", 2, 1)))
    private val catalog = SeriesCatalog("share", "catalog", SourceKind.EIGHT_MAPLE, "劇名", null, listOf(a, b))

    @Test fun selectionMovesOnlyOnExplicitPositiveSelectionAndTogglesOffToEmpty() {
        val first = EpisodeSelectionPolicy.toggle(Selection(), a, a.episodes[0].page.playbackUrl)
        val both = EpisodeSelectionPolicy.toggle(first, a, a.episodes[1].page.playbackUrl)
        assertEquals(2, both.urls.size)
        // Viewing is deliberately not an input to this policy; clearing the visible B page also leaves A intact.
        assertEquals(both, EpisodeSelectionPolicy.clearLine(both, b.id))
        val switched = EpisodeSelectionPolicy.toggle(both, b, b.episodes[0].page.playbackUrl)
        assertEquals(Selection(b.id, setOf(b.episodes[0].page.playbackUrl)), switched)
        assertEquals(Selection(), EpisodeSelectionPolicy.toggle(switched, b, b.episodes[0].page.playbackUrl))
        assertEquals(switched, EpisodeSelectionPolicy.toggle(switched, b, "https://unrelated"))
    }

    @Test fun selectAllIsExplicitAndClearOfAnotherLineCannotRemoveSelection() {
        val selected = EpisodeSelectionPolicy.selectAll(a)
        assertEquals(selected, EpisodeSelectionPolicy.clearLine(selected, b.id))
        assertEquals(Selection(), EpisodeSelectionPolicy.clearLine(selected, a.id))
        assertEquals(b.id, EpisodeSelectionPolicy.selectAll(b).lineId)
        assertEquals(Selection(), EpisodeSelectionPolicy.selectAll(b.copy(episodes = emptyList())))
    }

    @Test fun orderedSubmissionUsesNaturalNumbersThenUnknownSourceOrderAndRetainsLineIdentity() {
        val episodes = listOf(episode("1", null, 5, "預告後篇"), episode("1", 10, 4), episode("1", 2, 2),
            episode("1", 1, 1), episode("1", null, 0, "預告前篇"))
        val line = a.copy(episodes = episodes)
        val current = catalog.copy(lines = listOf(line))
        val selected = Selection(a.id, episodes.reversed().map { it.page.playbackUrl }.toSet())
        val pages = EpisodeSelectionPolicy.orderedPages(current, selected)
        assertEquals(listOf(1, 2, 10, null, null), pages.map { it.episodeNumber })
        assertEquals(listOf(0, 5), pages.takeLast(2).map { it.playbackUrl.substringAfterLast('-').substringBefore('.').toInt() })
        assertTrue(pages.all { it.catalogUrl == "catalog" && it.lineId == a.id && it.lineName == a.name && it.seriesTitle == "劇名" })
        assertTrue(pages.all { it.seasonNumber == null })
    }

    @Test fun restoredSelectionDropsRemovedUrlsAndMissingLinesWithoutInventingChoices() {
        val selected = Selection(a.id, setOf(a.episodes[0].page.playbackUrl, "removed"))
        assertEquals(setOf(a.episodes[0].page.playbackUrl), EpisodeSelectionPolicy.validate(catalog, selected).urls)
        assertNull(EpisodeSelectionPolicy.validate(catalog.copy(lines = listOf(b)), selected).lineId)
        assertEquals(emptyList(), EpisodeSelectionPolicy.orderedPages(catalog, Selection()))
    }

    @Test fun displayFallbackCannotBecomeConfirmedSeriesMetadata() {
        val unknown = a.copy(episodes = a.episodes.map { it.copy(page = it.page.copy(seriesTitle = null)) })
        val current = catalog.copy(title = "未命名影片 show", lines = listOf(unknown))
        val pages = EpisodeSelectionPolicy.orderedPages(current, EpisodeSelectionPolicy.selectAll(unknown))
        assertTrue(pages.all { it.seriesTitle == null && it.seasonNumber == null })
    }
}
