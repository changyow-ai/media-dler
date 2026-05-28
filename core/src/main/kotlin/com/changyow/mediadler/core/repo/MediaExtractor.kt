package com.changyow.mediadler.core.repo

import com.changyow.mediadler.core.model.MediaItem

/** Resolves a shared URL into the concrete media items it contains. */
interface MediaExtractor {
    suspend fun extract(url: String): Result<List<MediaItem>>
}
