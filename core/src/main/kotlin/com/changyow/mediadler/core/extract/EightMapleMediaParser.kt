package com.changyow.mediadler.core.extract

import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.MediaResource
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Reads the page's player data without executing its player or advertising scripts. */
object EightMapleMediaParser {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val scripts = Regex("""<script\b[^>]*>(.*?)</script\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val assignment = Regex("""\b(?:var\s+)?player_aaaa\s*=\s*""")
    private val titleTag = Regex("""<title\b[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val episodeLabel = Regex("""第\s*(\d+)\s*[集話]""")
    private val metaTag = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attribute = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    fun parse(html: String, pageUrl: String, originalUrl: String = pageUrl): MediaItem {
        val page = EightMapleUrl.playbackOrNull(pageUrl)
            ?: error("請分享 8maple 的單集播放頁網址")
        val blocks = scripts.findAll(html).mapNotNull { script ->
            val body = script.groupValues[1]
            assignment.find(body)?.let { jsonObjectAt(body, it.range.last + 1) }
        }.toList()
        check(blocks.size == 1) { "8maple 播放資料缺失或不明確，請重新分享播放頁" }
        val data = runCatching { Json.parseToJsonElement(blocks.single()) as? JsonObject }.getOrNull()
            ?: error("8maple 播放資料格式已變更，請稍後重試")
        check(data.text("id") == page.id && data.number("sid") == page.line && data.number("nid") == page.index) {
            "8maple 播放資料與這一集不符，請重新分享播放頁"
        }

        val rawUrl = data.text("url")?.takeIf { it.isNotBlank() }
            ?: error("8maple 沒有提供可下載的影片網址")
        val decoded = when (val encoding = data.number("encrypt")) {
            0 -> rawUrl
            1 -> unescape(rawUrl)
            2 -> runCatching { unescape(String(Base64.getDecoder().decode(rawUrl), Charsets.ISO_8859_1)) }
                .getOrElse { error("8maple 影片網址編碼無法解析，請重試或選擇其他播放線路") }
            else -> error("尚未支援這個 8maple 網址編碼（$encoding），請選擇其他播放線路")
        }
        val mediaUrl = httpUrl(decoded)
            ?: error("8maple 沒有提供有效的 HTTP 影片網址")
        val path = URI(mediaUrl).path.lowercase()
        check(path.endsWith(".m3u8") || path.endsWith(".mp4")) {
            "這條 8maple 播放線路未提供直接影片，請選擇其他播放線路"
        }
        val series = (data["vod_data"] as? JsonObject)?.text("vod_name")?.trim()?.takeIf { it.isNotBlank() }
        val originalTitle = titleTag.find(html)?.groupValues?.get(1)?.let(::htmlText)?.takeIf { it.isNotBlank() }
        val title = originalTitle ?: series ?: "8maple_${page.id}"
        val headers = mapOf("Referer" to pageUrl, "User-Agent" to USER_AGENT)
        val resource = MediaResource(mediaUrl, headers)
        val thumbnail = metaTag.findAll(html).mapNotNull { tag ->
            val attrs = attribute.findAll(tag.value).associate { a ->
                a.groupValues[1].lowercase() to htmlText(a.groupValues[2].ifEmpty { a.groupValues[3] })
            }
            attrs["content"]?.takeIf { attrs["property"].equals("og:image", ignoreCase = true) }
        }.firstOrNull()?.let { runCatching { URI(pageUrl).resolve(it).toString() }.getOrNull() }?.let(::httpUrl)

        return MediaItem(
            sourceUrl = mediaUrl,
            playlistIndex = null,
            id = "8maple_${page.id}_${page.line}_${page.index}",
            title = title,
            thumbnailUrl = thumbnail,
            durationSeconds = null,
            isImage = false,
            formats = listOf(MediaFormat("best", "mp4", "最佳畫質", null, true, true, false, null, resource)),
            source = SourceContext(originalUrl, pageUrl, SourceKind.EIGHT_MAPLE),
            downloadTarget = DownloadTarget.DIRECT,
            httpHeaders = headers,
            originalTitle = originalTitle,
            seriesTitle = series,
            // A playback-line/index is not evidence of a season or an episode number.
            episodeNumber = originalTitle?.let { episodeLabel.find(it)?.groupValues?.get(1)?.toIntOrNull() },
        )
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

    private fun jsonObjectAt(text: String, offset: Int): String? {
        val start = text.indexOfFirstFrom(offset) { !it.isWhitespace() }
        if (start < 0 || text[start] != '{') return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private inline fun String.indexOfFirstFrom(offset: Int, predicate: (Char) -> Boolean): Int {
        for (i in offset until length) if (predicate(this[i])) return i
        return -1
    }

    /** MacCMS uses JavaScript unescape, which preserves '+' (unlike form URL decoding). */
    private fun unescape(value: String): String = Regex("""%(?:u([0-9a-fA-F]{4})|([0-9a-fA-F]{2}))""")
        .replace(value) { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt(16).toChar().toString() }

    private fun httpUrl(value: String): String? = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme in setOf("https", "http") && it.host != null && it.rawUserInfo == null
    }?.toString()

    private fun htmlText(value: String): String = value.replace(Regex("""&#(x[0-9a-fA-F]+|\d+);""")) {
        val n = it.groupValues[1]
        val code = if (n.startsWith('x')) n.substring(1).toIntOrNull(16) else n.toIntOrNull()
        code?.takeIf(Character::isValidCodePoint)?.let { c -> String(Character.toChars(c)) } ?: it.value
    }.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&").trim()
}
