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
            if (!ThreadsUrl.isSupported(url)) {
                return@withContext Result.failure(IllegalArgumentException("不是 Threads 連結：\n$url"))
            }
            runCatching {
                val postUrl = resolvePostUrl(url)
                val embed = checkNotNull(ThreadsUrl.embedUrlOrNull(postUrl)) {
                    "Threads share 沒有導向貼文：\n$postUrl"
                }
                val conn = open(embed)
                val status = conn.responseCode
                val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                check(status in 200..299) { "Threads embed 回應 HTTP $status\nembed：$embed" }
                val items = ThreadsEmbedParser.parse(body, postUrl)
                check(items.isNotEmpty()) {
                    buildString {
                        append("Threads embed 找不到影片/圖片（可能為純文字、需登入，或多圖只暴露首圖）\n")
                        append("embed：").append(embed).append('\n')
                        append("頁面 ").append(body.length).append(" bytes")
                        append("，含 cdninstagram=").append(body.contains("cdninstagram"))
                        append("，含 <video>=").append(body.contains("<video"))
                        append("，含 OuterContainer=").append(body.contains("OuterContainer"))
                    }
                }
                items
            }
        }

    /**
     * Threads serves `/share/<token>` as a JS-only 404 shell to browser UAs, but
     * returns the canonical post as an HTTP redirect to non-browser clients.
     * Resolve at most two host redirects plus the post redirect, accepting only
     * another supported Threads URL at every hop.
     */
    private fun resolvePostUrl(sourceUrl: String): String {
        if (!ThreadsUrl.isShare(sourceUrl)) return sourceUrl

        var current = sourceUrl
        repeat(MAX_SHARE_REDIRECTS) {
            val conn = open(current, userAgent = SHARE_RESOLVER_UA, followRedirects = false)
            val status = conn.responseCode
            val location = conn.getHeaderField("Location")
            conn.disconnect()

            check(status in 300..399) {
                "Threads share 無法解析（HTTP $status）：\n$sourceUrl"
            }
            check(!location.isNullOrBlank()) {
                "Threads share redirect 缺少 Location：\n$sourceUrl"
            }

            val redirected = URL(URL(current), location).toString()
            if (ThreadsUrl.embedUrlOrNull(redirected) != null) return redirected
            check(ThreadsUrl.isShare(redirected)) {
                "Threads share 導向非貼文網址：\n$redirected"
            }
            current = redirected
        }
        error("Threads share redirect 過多：\n$sourceUrl")
    }

    private fun open(
        target: String,
        userAgent: String = UA,
        followRedirects: Boolean = true,
    ): HttpURLConnection =
        (URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Sec-Fetch-Mode", "navigate")
            setRequestProperty("Sec-Fetch-Dest", "document")
        }

    private companion object {
        const val MAX_SHARE_REDIRECTS = 3
        const val SHARE_RESOLVER_UA = "media-dler Android"
        const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
