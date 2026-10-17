package com.changyow.mediadler.download

import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.QueuedSpec
import com.changyow.mediadler.core.repo.MediaExtractor

/** Resolve only the claimed episode. Manual retries and expired background URLs return to its page. */
class QueuedRequestResolver(private val extractor: MediaExtractor) {
    suspend fun resolve(spec: QueuedSpec): DownloadRequest = when (spec) {
        is QueuedSpec.Resolved -> spec.request
        is QueuedSpec.Page -> {
            val items = extractor.extract(spec.page.playbackUrl).getOrThrow()
            check(items.size == 1 && !items.single().isImage && items.single().source.kind == spec.page.kind) {
                "這個播放頁未提供唯一的影片，請重新選擇單集"
            }
            val item = items.single()
            val enriched = item.copy(
                source = item.source.copy(originalUrl = spec.page.originalUrl),
                seriesTitle = item.seriesTitle ?: spec.page.seriesTitle,
                seasonNumber = item.seasonNumber ?: spec.page.seasonNumber,
                episodeNumber = item.episodeNumber ?: spec.page.episodeNumber,
                thumbnailUrl = item.thumbnailUrl ?: spec.page.thumbnailUrl,
            )
            DownloadRequest(spec.page.metadataOverride?.applyTo(enriched) ?: enriched, spec.selection)
        }
    }
}
