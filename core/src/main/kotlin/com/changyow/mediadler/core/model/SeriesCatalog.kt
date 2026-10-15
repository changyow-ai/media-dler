package com.changyow.mediadler.core.model

data class SeriesCatalog(
    val originalUrl: String,
    val pageUrl: String,
    val kind: SourceKind,
    val title: String,
    val thumbnailUrl: String?,
    val lines: List<PlaybackLine>,
)

data class PlaybackLine(val id: String, val name: String, val sourceOrder: Int, val episodes: List<CatalogEpisode>)

/** The original label and source order survive even when an episode number is unknown. */
data class CatalogEpisode(val label: String, val page: EpisodePage, val sourceOrder: Int)
