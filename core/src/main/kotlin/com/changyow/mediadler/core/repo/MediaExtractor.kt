package com.changyow.mediadler.core.repo

import com.changyow.mediadler.core.model.MediaItem

/**
 * Resolves a shared URL into media using the same contract for general and dedicated extractors.
 * Keep the shared URL in MediaItem.source.originalUrl, a re-resolvable page in source.pageUrl,
 * and the extractor-assigned source kind even when redirects/iframes/CDNs change the target.
 * Missing series/season/episode information stays null. Direct resources carry their own headers.
 */
interface MediaExtractor {
    suspend fun extract(url: String): Result<List<MediaItem>>
}
