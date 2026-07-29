package com.changyow.mediadler.data

import com.changyow.mediadler.core.extract.ThreadsUrl
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.repo.MediaExtractor

/**
 * Sends Threads URLs to the dedicated [threads] extractor; everything else goes to
 * [fallback] (yt-dlp).
 *
 * A failed Threads extraction deliberately gets NO yt-dlp fallback: yt-dlp's
 * html5-on-embed scrapes the whole page unscoped and may return a parent post's
 * media. The one exception is an Instagram Reel link already identified inside
 * the scoped shared-post block; [fallback] resolves that Reel permalink so its
 * real video formats replace the link-card preview.
 */
class RoutingMediaExtractor(
    private val threads: MediaExtractor,
    private val fallback: MediaExtractor,
) : MediaExtractor {
    override suspend fun extract(url: String): Result<List<MediaItem>> {
        if (!ThreadsUrl.isSupported(url)) return fallback.extract(url)

        return threads.extract(url).fold(
            onSuccess = { resolveLinkedReels(it) },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun resolveLinkedReels(items: List<MediaItem>): Result<List<MediaItem>> {
        val resolvedItems = ArrayList<MediaItem>()
        for (placeholder in items) {
            if (!INSTAGRAM_REEL.matches(placeholder.sourceUrl)) {
                resolvedItems += placeholder
                continue
            }

            val resolved = fallback.extract(placeholder.sourceUrl)
                .getOrElse { return Result.failure(it) }
            resolved.forEachIndexed { index, item ->
                val suffix = if (resolved.size > 1) "_${index + 1}" else ""
                resolvedItems += item.copy(
                    id = placeholder.id + suffix,
                    title = placeholder.title + suffix,
                    thumbnailUrl = item.thumbnailUrl ?: placeholder.thumbnailUrl,
                )
            }
        }
        return Result.success(resolvedItems)
    }

    private companion object {
        val INSTAGRAM_REEL = Regex(
            """^https?://(?:www\.)?instagram\.com/reel/[^/?#]+/?(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
