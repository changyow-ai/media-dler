package com.changyow.mediadler.data.threads

import com.changyow.mediadler.core.extract.ThreadsEmbedParser
import com.changyow.mediadler.core.extract.ThreadsUrl
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.repo.MediaExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * yt-dlp has no Threads extractor and its html5 fallback often lands on the
 * "_fb_noscript" page (no <video>). We fetch the /embed page with a browser UA
 * ourselves and pull the direct CDN media URLs; the generic downloader then
 * fetches them directly.
 */
class ThreadsExtractor : MediaExtractor {

    override suspend fun extract(url: String): Result<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            val embed = ThreadsUrl.embedUrlOrNull(url)
                ?: return@withContext Result.failure(IllegalArgumentException("不是 Threads 連結：\n$url"))
            runCatching {
                val conn = open(embed)
                val status = conn.responseCode
                val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                check(status in 200..299) { "Threads embed 回應 HTTP $status\nembed：$embed" }
                val items = ThreadsEmbedParser.parse(body, url)
                check(items.isNotEmpty()) {
                    buildString {
                        append("Threads embed 找不到影片/圖片（可能為純文字、需登入，或多圖只暴露首圖）\n")
                        append("embed：").append(embed).append('\n')
                        append("頁面 ").append(body.length).append(" bytes")
                        append("，含 cdninstagram=").append(body.contains("cdninstagram"))
                        append("，含 <video>=").append(body.contains("<video"))
                    }
                }
                items
            }
        }

    private fun open(target: String): HttpURLConnection =
        (URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Sec-Fetch-Mode", "navigate")
            setRequestProperty("Sec-Fetch-Dest", "document")
        }

    private companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
