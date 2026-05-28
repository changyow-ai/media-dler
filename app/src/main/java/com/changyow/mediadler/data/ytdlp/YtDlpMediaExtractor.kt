package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.extract.YtDlpInfoParser
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.repo.MediaExtractor
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YtDlpMediaExtractor(
    private val engine: EngineInitializer,
) : MediaExtractor {

    override suspend fun extract(url: String): Result<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            val state = engine.ensureInit()
            if (state is EngineState.Failed) {
                return@withContext Result.failure(IllegalStateException(state.message))
            }
            runCatching { doExtract(url) }
                .recoverCatching {
                    // Self-heal: a stale bundled yt-dlp commonly fails (esp. YouTube).
                    // Refresh the engine once and retry before giving up.
                    engine.update()
                    doExtract(url)
                }
        }

    private fun doExtract(url: String): List<MediaItem> {
        val request = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--no-warnings")
            addOption("--playlist-items", "1:50")
        }
        val response = YoutubeDL.getInstance().execute(request)
        val items = YtDlpInfoParser.parse(response.out, url)
        check(items.isNotEmpty()) { "找不到可下載的媒體（可能不支援此網站或需要登入）" }
        return items
    }
}
