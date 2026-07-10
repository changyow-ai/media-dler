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
 * extraction to that block (see [scopeToSharedPost]). When the page has several
 * post blocks but none can be identified as the shared one, we return NO media
 * rather than guess — a clear "no media" failure beats silently extracting the
 * wrong post's video. Videos live on `*.fbcdn.net` (not cdninstagram), so the URL
 * matcher covers both hosts.
 */
object ThreadsEmbedParser {
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "heic")
    private val MEDIA_URL =
        Regex("""https://[a-z0-9_.-]*(?:cdninstagram\.com|fbcdn\.net)/[^"'\\ )<>]+""", RegexOption.IGNORE_CASE)
    private val PROFILE_PIC = Regex("""/t51\.\d+-19/""")
    // Each rendered post in the embed opens with a div carrying the OuterContainer class.
    // The class token must START with "OuterContainer": link-preview cards inside a post use
    // "LinkAttachmentOuterContainer", and treating them as post boundaries cuts the preview
    // image out of the shared post's scope.
    private val POST_BLOCK = Regex("""<div[^>]*class="(?:[^"]*\s)?OuterContainer[^"]*"""", RegexOption.IGNORE_CASE)

    fun parse(embedHtml: String, postUrl: String): List<MediaItem> {
        // Decode the entities/escapes the embed JSON uses, including `&#064;`/`&#64;` → `@` so
        // author handles in hrefs (`/&#064;user?…`) are matchable by scopeToSharedPost.
        val html = embedHtml
            .replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/")
            .replace("&#064;", "@").replace("&#64;", "@")
        val code = ThreadsUrl.postCode(postUrl) ?: "threads"
        val scope = scopeToSharedPost(html, postUrl)

        val seen = HashSet<String>()
        val videos = ArrayList<String>()
        val images = ArrayList<String>()
        for (match in MEDIA_URL.findAll(scope)) {
            val url = match.value
            if (isStaticAsset(url)) continue
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
     *  2. the block whose permalink carries the shared post's short-code (unique only),
     *  3. the block referencing the URL's author handle (unique only; `@` is decoded),
     *  4. the only block, if there's just one.
     * When no blocks are present at all (older/simple embeds and unit fixtures) the
     * whole page is returned, preserving the original behaviour. But when there ARE
     * several blocks and none can be identified, we return "" — refusing to guess —
     * so the caller surfaces a "no media" error instead of the wrong post's media.
     */
    private fun scopeToSharedPost(html: String, postUrl: String): String {
        val starts = POST_BLOCK.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return html
        val bounds = starts + html.length
        val blocks = starts.indices.map { html.substring(bounds[it], bounds[it + 1]) }

        blocks.firstOrNull { it.contains("OuterContainerFull") }?.let { return it }
        ThreadsUrl.postCode(postUrl)?.let { code ->
            blocks.singleOrNull { it.contains("/post/$code") }?.let { return it }
        }
        ThreadsUrl.handle(postUrl)?.let { handle ->
            blocks.singleOrNull {
                it.contains("/@$handle?") || it.contains("/@$handle\"") || it.contains("/@$handle/")
            }?.let { return it }
        }
        blocks.singleOrNull()?.let { return it }
        return ""
    }

    /**
     * Static UI assets (sprites, emoji, JS/CSS) live on `static.*` CDN hosts or under
     * `/rsrc.php` — they are not post media. Excludes both `static.cdninstagram.com`
     * and `static.*.fbcdn.net`, which the broadened [MEDIA_URL] now also matches.
     */
    private fun isStaticAsset(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').lowercase()
        return host.startsWith("static.") || url.contains("/rsrc.php")
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
