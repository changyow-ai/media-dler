package com.changyow.mediadler.core.model

import kotlinx.serialization.Serializable

/** Source identity is assigned by the page's extractor, never inferred from a media CDN. */
@Serializable enum class SourceKind { GENERAL, GIMY, EIGHT_MAPLE }

/** [pageUrl] can be resolved again when a signed media URL expires. */
@Serializable data class SourceContext(
    val originalUrl: String,
    val pageUrl: String,
    val kind: SourceKind = SourceKind.GENERAL,
)

/** A resource observed during extraction; its URL and headers may be short-lived. */
@Serializable data class MediaResource(
    val url: String,
    val httpHeaders: Map<String, String> = emptyMap(),
)

@Serializable enum class DownloadTarget { PAGE, DIRECT }

/** A single downloadable piece of media resolved from a shared URL. */
@Serializable data class MediaItem(
    /** yt-dlp target: a re-extractable page for PAGE, a media resource for DIRECT. */
    val sourceUrl: String,
    val playlistIndex: Int?,
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val isImage: Boolean,
    val formats: List<MediaFormat>,
    val source: SourceContext = SourceContext(originalUrl = sourceUrl, pageUrl = sourceUrl),
    val downloadTarget: DownloadTarget = DownloadTarget.PAGE,
    val httpHeaders: Map<String, String> = emptyMap(),
    val originalTitle: String? = title,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

/** One selectable output format for a [MediaItem]. */
@Serializable data class MediaFormat(
    val formatId: String,
    val ext: String,
    val label: String,
    val height: Int?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val isImage: Boolean,
    val filesizeBytes: Long?,
    val resource: MediaResource? = null,
)

/** HTTP header names are case-insensitive; resource-specific values override defaults. */
fun mergeHttpHeaders(base: Map<String, String>, overrides: Map<String, String>): Map<String, String> =
    LinkedHashMap(base).apply {
        overrides.forEach { (name, value) ->
            keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { remove(it) }
            put(name, value)
        }
    }
