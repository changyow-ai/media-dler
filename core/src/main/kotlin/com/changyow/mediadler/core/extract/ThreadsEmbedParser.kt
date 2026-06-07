package com.changyow.mediadler.core.extract

import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem

/**
 * Extracts direct CDN media URLs (videos + post images) from a Threads `/embed`
 * page's HTML. Avatars (profile-pic namespace) and static UI assets are skipped.
 *
 * An embed for a reply renders the whole conversation: the parent post(s) as
 * context above, then the shared post itself. We must extract media from only the
 * shared post — otherwise sharing a reply yields the main post's media. The embed
 * marks the shared post's block with the `OuterContainerFull` class, so we scope
 * extraction to that block (see [scopeToSharedPost]). Videos live on `*.fbcdn.net`
 * (not cdninstagram), so the URL matcher covers both hosts.
 */
object ThreadsEmbedParser {
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "heic")
    private val MEDIA_URL =
        Regex("""https://[a-z0-9_.-]*(?:cdninstagram\.com|fbcdn\.net)/[^"'\\ )<>]+""", RegexOption.IGNORE_CASE)
    private val PROFILE_PIC = Regex("""/t51\.\d+-19/""")
    // Each rendered post in the embed opens with a div carrying the OuterContainer class.
    private val POST_BLOCK = Regex("""<div[^>]*class="[^"]*OuterContainer[^"]*"""", RegexOption.IGNORE_CASE)

    fun parse(embedHtml: String, postUrl: String): List<MediaItem> {
        val html = embedHtml.replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/")
        val code = ThreadsUrl.postCode(postUrl) ?: "threads"
        val scope = scopeToSharedPost(html, postUrl)

        val seen = HashSet<String>()
        val videos = ArrayList<String>()
        val images = ArrayList<String>()
        for (match in MEDIA_URL.findAll(scope)) {
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

    /**
     * Narrows [html] to just the shared post's rendered block so we don't pull in
     * parent/context posts' media. Splits the page into per-post blocks (each opens
     * with an `OuterContainer` div) and picks, in order of confidence:
     *  1. the block marked `OuterContainerFull` (the post the embed points at),
     *  2. the block referencing the URL's author handle,
     *  3. the only block, if there's just one.
     * Falls back to the whole page when no blocks are present (older/simple embeds
     * and unit fixtures), preserving the original whole-page behaviour.
     */
    private fun scopeToSharedPost(html: String, postUrl: String): String {
        val starts = POST_BLOCK.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return html
        val bounds = starts + html.length
        val blocks = starts.indices.map { html.substring(bounds[it], bounds[it + 1]) }

        blocks.firstOrNull { it.take(220).contains("OuterContainerFull") }?.let { return it }
        ThreadsUrl.handle(postUrl)?.let { handle ->
            blocks.firstOrNull { it.contains("/$handle?") || it.contains("/$handle\"") }?.let { return it }
        }
        return blocks.singleOrNull() ?: html
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
