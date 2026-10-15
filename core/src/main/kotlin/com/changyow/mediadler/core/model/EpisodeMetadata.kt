package com.changyow.mediadler.core.model

import kotlinx.serialization.Serializable

/** A complete user correction: null explicitly means unknown, rather than "use extracted data". */
@Serializable data class EpisodeMetadata(
    val title: String,
    val seriesTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
) {
    init {
        require(title.isNotBlank()) { "請輸入標題" }
        require(seasonNumber == null || seasonNumber > 0) { "季數必須是正整數" }
        require(episodeNumber == null || episodeNumber > 0) { "集數必須是正整數" }
    }

    fun applyTo(task: DownloadTask): DownloadTask = task.copy(
        title = title, seriesTitle = seriesTitle, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
    )

    fun applyTo(item: MediaItem): MediaItem = item.copy(
        title = title, seriesTitle = seriesTitle, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
    )
}
