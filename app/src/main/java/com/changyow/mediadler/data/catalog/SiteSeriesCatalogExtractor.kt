package com.changyow.mediadler.data.catalog

import com.changyow.mediadler.core.extract.EightMapleMediaParser
import com.changyow.mediadler.core.extract.EightMapleUrl
import com.changyow.mediadler.core.extract.GimyMediaParser
import com.changyow.mediadler.core.extract.GimyUrl
import com.changyow.mediadler.core.extract.SeriesCatalogParser
import com.changyow.mediadler.core.model.SeriesCatalog
import com.changyow.mediadler.core.repo.SeriesCatalogExtractor
import com.changyow.mediadler.data.web.SitePageClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A catalog request reads one homepage only, never any of its episode players. */
class SiteSeriesCatalogExtractor(private val client: SitePageClient = SitePageClient()) : SeriesCatalogExtractor {
    override suspend fun extractCatalog(url: String): Result<SeriesCatalog> = withContext(Dispatchers.IO) {
        try {
            val eightMaple = EightMapleUrl.catalogId(url) != null
            check(eightMaple || GimyUrl.catalogId(url) != null) { "請分享 Gimy 或 8maple 的影片首頁網址" }
            val page = client.read(
                url, if (eightMaple) "8maple" else "Gimy",
                if (eightMaple) EightMapleMediaParser.USER_AGENT else GimyMediaParser.USER_AGENT,
                allowed = { if (eightMaple) EightMapleUrl.catalogId(it) != null else GimyUrl.catalogId(it) != null },
            )
            Result.success(SeriesCatalogParser.parse(page.html, page.url, url))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
