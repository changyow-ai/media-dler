package com.changyow.mediadler.data.gimy

import com.changyow.mediadler.core.extract.GimyMediaParser
import com.changyow.mediadler.core.extract.GimyUrl
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.repo.MediaExtractor
import com.changyow.mediadler.data.web.SitePageClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GimyExtractor(private val client: SitePageClient = SitePageClient()) : MediaExtractor {
    override suspend fun extract(url: String): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            check(GimyUrl.playbackId(url) != null) { "請分享 Gimy 的單集播放頁網址" }
            val page = client.read(
                url, "Gimy", GimyMediaParser.USER_AGENT,
                allowed = { GimyUrl.playbackId(it) != null },
            )
            if (GimyUrl.playbackOrNull(page.url) != null) {
                return@withContext Result.success(listOf(GimyMediaParser.parsePage(page.html, page.url, url)))
            }
            val playerUrl = GimyMediaParser.playerUrl(page.html, page.url)
            val player = client.read(
                playerUrl, "Gimy", GimyMediaParser.USER_AGENT,
                allowed = GimyUrl::isPlayer, referer = page.url,
            )
            Result.success(listOf(GimyMediaParser.parse(page.html, player.html, page.url, player.url, url)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
