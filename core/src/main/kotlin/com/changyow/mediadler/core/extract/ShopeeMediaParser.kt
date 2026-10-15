package com.changyow.mediadler.core.extract

import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.MediaResource
import com.changyow.mediadler.core.model.SourceContext
import java.net.URI
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Consumes only the matching item's normalized public DOM snapshot, never whole-page media. */
object ShopeeMediaParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val HASH = Regex("[A-Za-z0-9_-]{1,200}")
    private const val IMAGE_BASE = "https://down-tw.img.susercontent.com/file/"

    /** An invalid or incomplete snapshot returns no items, including no partial image gallery. */
    fun parse(snapshotJson: String, product: ShopeeUrl.Product): List<MediaItem> {
        val snapshot = runCatching { json.decodeFromString<Snapshot>(snapshotJson) }.getOrNull()
            ?: return emptyList()
        if (!snapshot.documentMatches || !snapshot.found ||
            ShopeeUrl.productOrNull(snapshot.documentUrl) != product ||
            snapshot.shopId != product.shopId || snapshot.itemId != product.itemId ||
            !snapshot.galleryComplete || snapshot.imageHashes.size !in 1..50 ||
            snapshot.gallerySourceCount != snapshot.imageHashes.size ||
            snapshot.imageHashes.any { !HASH.matches(it) } ||
            snapshot.videos.size !in 0..8 || snapshot.videoSourceCount != snapshot.videos.size ||
            (snapshot.itemHasVideo && snapshot.videos.isEmpty())
        ) return emptyList()

        val items = ArrayList<MediaItem>()
        snapshot.videos.forEachIndexed { index, video ->
            if (video.formats.size !in 1..64 || video.formats.any { !isMp4(it.url) }) return emptyList()
            val format = video.formats.maxWithOrNull(
                compareBy<VideoFormat> { codecRank(it.codec) }
                    .thenBy { it.width.coerceAtLeast(0).toLong() * it.height.coerceAtLeast(0) }
                    .thenBy { it.height },
            ) ?: return emptyList()
            val name = "ShopeeVideo_${product.shopId}_${product.itemId}_${index + 1}"
            items += media(
                format.url, name, isImage = false,
                thumbnail = thumbnailUrl(video.thumbnail),
                duration = video.durationSeconds?.takeIf { it > 0 },
                height = format.height.takeIf { it > 0 },
                size = format.size?.longOrNull?.takeIf { it > 0 },
            )
        }
        snapshot.imageHashes.forEachIndexed { index, hash ->
            val url = IMAGE_BASE + hash
            val name = "ShopeeImage_${product.shopId}_${product.itemId}_${index + 1}"
            items += media(url, name, isImage = true, thumbnail = url)
        }
        return items.map { item ->
            item.copy(source = SourceContext(product.canonicalUrl, product.canonicalUrl))
        }
    }

    private fun codecRank(codec: String): Int = when (codec.uppercase(Locale.ROOT).replace(".", "")) {
        "H264", "AVC", "AVC1" -> 2
        "H265", "HEVC", "HEV1", "HVC1" -> 0
        else -> 1 // Native public data can omit codec metadata entirely.
    }

    private fun isMp4(url: String): Boolean {
        val uri = cdnUri(url, ".vod.susercontent.com") ?: return false
        return uri.rawPath.orEmpty().endsWith(".mp4", ignoreCase = true)
    }

    private fun thumbnailUrl(value: String?): String? = when {
        value == null -> null
        HASH.matches(value) -> IMAGE_BASE + value
        cdnUri(value, ".img.susercontent.com") != null -> value
        else -> null
    }

    private fun cdnUri(url: String, suffix: String): URI? {
        if (url.length > 4096) return null
        return runCatching { URI(url) }.getOrNull()?.takeIf {
            it.scheme.equals("https", ignoreCase = true) && it.rawUserInfo == null &&
                (it.port == -1 || it.port == 443) &&
                it.host?.lowercase(Locale.ROOT)?.endsWith(suffix) == true &&
                !it.rawPath.isNullOrEmpty()
        }
    }

    private fun media(
        url: String,
        name: String,
        isImage: Boolean,
        thumbnail: String?,
        duration: Long? = null,
        height: Int? = null,
        size: Long? = null,
    ): MediaItem = MediaItem(
        sourceUrl = url,
        playlistIndex = null,
        id = name,
        title = name,
        thumbnailUrl = thumbnail,
        durationSeconds = duration,
        isImage = isImage,
        formats = listOf(
            MediaFormat(
                formatId = "best",
                ext = if (isImage) "jpg" else "mp4",
                label = if (isImage) "圖片" else "影片",
                height = height,
                hasVideo = !isImage,
                hasAudio = !isImage,
                isImage = isImage,
                filesizeBytes = size,
                resource = MediaResource(url),
            ),
        ),
        downloadTarget = DownloadTarget.DIRECT,
    )

    @Serializable private data class Snapshot(
        val documentUrl: String,
        val documentMatches: Boolean,
        val found: Boolean,
        val shopId: String,
        val itemId: String,
        val gallerySourceCount: Int,
        val galleryComplete: Boolean,
        val imageHashes: List<String>,
        val itemHasVideo: Boolean,
        val videoSourceCount: Int,
        val videos: List<Video>,
    )

    @Serializable private data class Video(
        val id: String = "",
        val durationSeconds: Long? = null,
        val thumbnail: String? = null,
        val formats: List<VideoFormat>,
    )

    @Serializable private data class VideoFormat(
        val url: String,
        val width: Int = 0,
        val height: Int = 0,
        val size: JsonPrimitive? = null,
        val codec: String = "",
    )
}
