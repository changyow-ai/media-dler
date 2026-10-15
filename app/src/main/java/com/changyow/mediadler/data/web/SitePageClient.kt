package com.changyow.mediadler.data.web

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Bounded static-page reads; each caller supplies its exact site and page-type boundary. */
class SitePageClient(
    private val maxChars: Int = 2 * 1024 * 1024,
    private val maxRequests: Int = 5,
) {
    init {
        require(maxChars > 0)
        require(maxRequests > 0)
    }

    data class Page(val url: String, val html: String)

    suspend fun read(
        url: String,
        siteName: String,
        userAgent: String,
        allowed: (String) -> Boolean,
        referer: String? = null,
    ): Page = withContext(Dispatchers.IO) {
        runInterruptible {
            var current = url.substringBefore('#')
            val visited = mutableSetOf<String>()
            repeat(maxRequests) {
                check(allowed(current)) { "$siteName 未導向支援的頁面，請重新分享網址" }
                check(visited.add(current)) { "$siteName 頁面發生循環轉址，請重新分享網址" }
                val connection = URL(current).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", userAgent)
                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    referer?.let { connection.setRequestProperty("Referer", it) }
                    val status = connection.responseCode
                    if (status in 300..399) {
                        val location = connection.getHeaderField("Location")?.takeIf { it.isNotBlank() }
                            ?: error("$siteName 轉址缺少目的地，請稍後重試")
                        val next = URI(current).resolve(location).toString().substringBefore('#')
                        check(allowed(next)) { "$siteName 未導向支援的頁面，請重新分享網址" }
                        check(next !in visited) { "$siteName 頁面發生循環轉址，請重新分享網址" }
                        current = next
                    } else {
                        check(status in 200..299) { "$siteName 頁面回應 HTTP $status，請稍後重試" }
                        val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            val text = StringBuilder()
                            val buffer = CharArray(8192)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                check(count <= maxChars - text.length) { "$siteName 頁面資料過大，無法解析" }
                                text.append(buffer, 0, count)
                            }
                            text.toString()
                        }
                        return@runInterruptible Page(current, html)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            error("$siteName 頁面轉址過多，請重新分享網址")
        }
    }
}
