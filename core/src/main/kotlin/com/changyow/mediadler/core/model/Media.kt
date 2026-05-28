package com.changyow.mediadler.core.model

/** A single downloadable piece of media resolved from a shared URL. */
data class MediaItem(
    val sourceUrl: String,
    val playlistIndex: Int?,
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val isImage: Boolean,
    val formats: List<MediaFormat>,
)

/** One selectable output format for a [MediaItem]. */
data class MediaFormat(
    val formatId: String,
    val ext: String,
    val label: String,
    val height: Int?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val isImage: Boolean,
    val filesizeBytes: Long?,
)
