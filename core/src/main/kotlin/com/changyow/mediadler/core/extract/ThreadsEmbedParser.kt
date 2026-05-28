package com.changyow.mediadler.core.extract

import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem

/**
 * Extracts direct CDN media URLs (videos + post images) from a Threads `/embed`
 * page's HTML. Avatars (profile-pic namespace) and static UI assets are skipped.
 */
object ThreadsEmbedParser {
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "heic")
    private val CDN_URL = Regex("""https://[a-z0-9_.-]*cdninstagram\.com/[^"'\\ )<>]+""", RegexOption.IGNORE_CASE)
    private val PROFILE_PIC = Regex("""/t51\.\d+-19/""")

    fun parse(embedHtml: String, postUrl: String): List<MediaItem> {
        val html = embedHtml.replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/")
        val code = ThreadsUrl.postCode(postUrl) ?: "threads"

        val seen = HashSet<String>()
        val videos = ArrayList<String>()
        val images = ArrayList<String>()
        for (match in CDN_URL.findAll(html)) {
            val url = match.value
            if (url.contains("static.cdninstagram.com")) continue
            val path = url.substringBefore('?')
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext != "mp4" && ext !in IMAGE_EXTS) continue
            if (!seen.add(path)) continue
            if (ext == "mp4") videos += url else if (!PROFILE_PIC.containsMatchIn(url)) images += url
        }

        val items = ArrayList<MediaItem>()
        videos.forEachIndexed { i, u ->
            items += build(u, "ThreadsVideo_$code" + if (videos.size > 1) "_${i + 1}" else "", isImage = false)
        }
        images.forEachIndexed { i, u ->
            items += build(u, "ThreadsImage_${code}_${i + 1}", isImage = true)
        }
        return items
    }

    private fun build(url: String, name: String, isImage: Boolean): MediaItem {
        val ext = url.substringBefore('?').substringAfterLast('.', if (isImage) "jpg" else "mp4").lowercase()
        return MediaItem(
            sourceUrl = url,
            playlistIndex = null,
            id = name,
            title = name,
            thumbnailUrl = if (isImage) url else null,
            durationSeconds = null,
            isImage = isImage,
            formats = listOf(
                MediaFormat(
                    formatId = "0",
                    ext = ext,
                    label = if (isImage) "圖片" else "影片",
                    height = null,
                    hasVideo = !isImage,
                    hasAudio = !isImage,
                    isImage = isImage,
                    filesizeBytes = null,
                ),
            ),
        )
    }
}
