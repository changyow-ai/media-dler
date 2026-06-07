package com.changyow.mediadler.data

import com.changyow.mediadler.core.extract.ThreadsUrl
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.repo.MediaExtractor

/**
 * Sends Threads URLs to the dedicated [threads] extractor; everything else goes to
 * [fallback] (yt-dlp).
 *
 * Threads deliberately gets NO yt-dlp fallback: yt-dlp's html5-on-embed scrapes the
 * whole embed page unscoped, so on a reply link it would return the parent post's
 * media — defeating [threads]'s shared-post scoping. A failed Threads extraction
 * surfaces as an error (better than the wrong video) rather than silently falling
 * through to the unscoped path.
 */
class RoutingMediaExtractor(
    private val threads: MediaExtractor,
    private val fallback: MediaExtractor,
) : MediaExtractor {
    override suspend fun extract(url: String): Result<List<MediaItem>> =
        if (ThreadsUrl.embedUrlOrNull(url) != null) {
            threads.extract(url)
        } else {
            fallback.extract(url)
        }
}
