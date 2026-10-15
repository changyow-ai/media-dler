package com.changyow.mediadler.data.eightmaple

import com.changyow.mediadler.core.extract.EightMapleMediaParser
import com.changyow.mediadler.core.extract.EightMapleUrl
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.repo.MediaExtractor
import com.changyow.mediadler.data.web.SitePageClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves only public 8maple player data; no browser, login or page-script execution. */
class EightMapleExtractor(private val client: SitePageClient = SitePageClient()) : MediaExtractor {
    override suspend fun extract(url: String): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            check(EightMapleUrl.playbackOrNull(url) != null) { "請分享 8maple 的單集播放頁網址" }
            val page = client.read(
                url, "8maple", EightMapleMediaParser.USER_AGENT,
                allowed = { EightMapleUrl.playbackOrNull(it) != null },
            )
            Result.success(listOf(EightMapleMediaParser.parse(page.html, page.url, originalUrl = url)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
