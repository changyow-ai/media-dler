package com.changyow.mediadler.data

import com.changyow.mediadler.core.extract.ThreadsUrl
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.repo.MediaExtractor

/**
 * Sends Threads URLs to the dedicated [threads] extractor (falling back to
 * [fallback], i.e. yt-dlp's html5-on-embed, if it finds nothing); everything
 * else goes straight to [fallback].
 */
class RoutingMediaExtractor(
    private val threads: MediaExtractor,
    private val fallback: MediaExtractor,
) : MediaExtractor {
    override suspend fun extract(url: String): Result<List<MediaItem>> =
        if (ThreadsUrl.embedUrlOrNull(url) != null) {
            threads.extract(url).recoverCatching { primary ->
                fallback.extract(url).getOrElse { throw primary }
            }
        } else {
            fallback.extract(url)
        }
}
