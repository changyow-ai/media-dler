package com.changyow.mediadler.core.download

import com.changyow.mediadler.core.model.EpisodePage
import com.changyow.mediadler.core.model.PlaybackLine
import com.changyow.mediadler.core.model.SeriesCatalog

/** Unsubmitted choices belong to one line; merely viewing a different line never mutates them. */
object EpisodeSelectionPolicy {
    data class Selection(val lineId: String? = null, val urls: Set<String> = emptySet())

    fun validate(catalog: SeriesCatalog, selection: Selection): Selection {
        val line = catalog.lines.firstOrNull { it.id == selection.lineId } ?: return Selection()
        val available = line.episodes.map { it.page.playbackUrl }.toSet()
        val urls = selection.urls.intersect(available)
        return if (urls.isEmpty()) Selection() else Selection(line.id, urls)
    }

    fun toggle(selection: Selection, line: PlaybackLine, url: String): Selection {
        if (line.episodes.none { it.page.playbackUrl == url }) return selection
        if (selection.lineId != line.id) return Selection(line.id, setOf(url))
        val urls = if (url in selection.urls) selection.urls - url else selection.urls + url
        return if (urls.isEmpty()) Selection() else Selection(line.id, urls)
    }

    fun selectAll(line: PlaybackLine): Selection {
        val urls = line.episodes.map { it.page.playbackUrl }.toSet()
        return if (urls.isEmpty()) Selection() else Selection(line.id, urls)
    }

    fun clearLine(selection: Selection, lineId: String): Selection =
        if (selection.lineId == lineId) Selection() else selection

    fun orderedPages(catalog: SeriesCatalog, selection: Selection): List<EpisodePage> {
        val valid = validate(catalog, selection)
        val line = catalog.lines.firstOrNull { it.id == valid.lineId } ?: return emptyList()
        return line.episodes.filter { it.page.playbackUrl in valid.urls }
            .sortedWith(compareBy({ it.page.episodeNumber?.takeIf { number -> number > 0 } == null },
                { it.page.episodeNumber?.takeIf { number -> number > 0 } ?: 0 },
                { it.sourceOrder }))
            .distinctBy { it.page.playbackUrl }
            .map { episode -> episode.page.copy(
                catalogUrl = catalog.pageUrl, lineId = line.id, lineName = line.name,
            ) }
    }
}
