package com.changyow.mediadler.core.repo

import com.changyow.mediadler.core.model.SeriesCatalog

interface SeriesCatalogExtractor {
    suspend fun extractCatalog(url: String): Result<SeriesCatalog>
}
