package com.changyow.mediadler.core.extract

import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object YtDlpInfoParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")

    /** Parse the JSON produced by `yt-dlp -J <url>` into a flat list of [MediaItem]. */
    fun parse(infoJson: String, requestUrl: String): List<MediaItem> {
        val root = json.decodeFromString<RawInfo>(infoJson)
        val entries = root.entries
        val items = if (!entries.isNullOrEmpty()) {
            entries.mapIndexedNotNull { i, e -> e?.toMediaItem(requestUrl, i + 1) }
        } else {
            listOf(root.toMediaItem(root.webpageUrl ?: requestUrl, null))
        }
        // Drop items with nothing downloadable (e.g. nested-playlist containers).
        return items.filter { it.formats.isNotEmpty() }
    }

    private fun RawInfo.toMediaItem(sourceUrl: String, playlistIndex: Int?): MediaItem {
        val parsed = (formats ?: emptyList()).mapNotNull { it.toMediaFormat() }
        val media = parsed.filter { it.hasVideo || it.hasAudio }
        val images = parsed.filter { it.isImage }

        val (isImage, chosen) = when {
            media.isNotEmpty() -> false to media
            images.isNotEmpty() -> true to images
            url != null -> {
                val path = url.substringBefore('?').substringBefore('#')
                val e = ext ?: path.substringAfterLast('/').substringAfterLast('.', "")
                val img = e.lowercase() in IMAGE_EXTS
                img to listOf(syntheticFormat(e, img))
            }
            else -> false to emptyList()
        }
        return MediaItem(
            sourceUrl = sourceUrl,
            playlistIndex = playlistIndex,
            id = id ?: "",
            title = title ?: id ?: "media",
            thumbnailUrl = thumbnail,
            durationSeconds = duration?.toLong(),
            isImage = isImage,
            formats = chosen,
        )
    }

    private fun syntheticFormat(ext: String, isImage: Boolean) = MediaFormat(
        formatId = "best",
        ext = ext.ifBlank { if (isImage) "jpg" else "mp4" },
        label = if (isImage) "Image" else "Video",
        height = null,
        hasVideo = !isImage,
        hasAudio = !isImage,
        isImage = isImage,
        filesizeBytes = null,
    )

    private fun RawFormat.toMediaFormat(): MediaFormat? {
        val e = (ext ?: "").lowercase()
        val isImage = e in IMAGE_EXTS
        val hasVideo = vcodec != null && vcodec != "none"
        val hasAudio = acodec != null && acodec != "none"
        // Drop storyboards / non-media rows (e.g. mhtml previews).
        if (!isImage && !hasVideo && !hasAudio) return null
        val fid = formatId ?: return null
        return MediaFormat(
            formatId = fid,
            ext = e.ifBlank { "bin" },
            label = buildLabel(isImage, hasVideo, hasAudio, height, e),
            height = height,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            isImage = isImage,
            filesizeBytes = filesize ?: filesizeApprox,
        )
    }

    private fun buildLabel(
        isImage: Boolean,
        hasVideo: Boolean,
        hasAudio: Boolean,
        height: Int?,
        ext: String,
    ): String = when {
        isImage -> "Image ${ext.uppercase()}"
        hasVideo && height != null -> "${height}p $ext" + if (!hasAudio) " (video only)" else ""
        hasVideo -> "Video $ext" + if (!hasAudio) " (video only)" else ""
        else -> "Audio $ext"
    }

    @Serializable
    private data class RawInfo(
        @SerialName("_type") val type: String? = null,
        val id: String? = null,
        val title: String? = null,
        val ext: String? = null,
        val thumbnail: String? = null,
        val duration: Double? = null,
        @SerialName("webpage_url") val webpageUrl: String? = null,
        val url: String? = null,
        val formats: List<RawFormat>? = null,
        val entries: List<RawInfo?>? = null,
    )

    @Serializable
    private data class RawFormat(
        @SerialName("format_id") val formatId: String? = null,
        val ext: String? = null,
        val vcodec: String? = null,
        val acodec: String? = null,
        val height: Int? = null,
        val width: Int? = null,
        @SerialName("format_note") val formatNote: String? = null,
        val filesize: Long? = null,
        @SerialName("filesize_approx") val filesizeApprox: Long? = null,
        val url: String? = null,
    )
}
