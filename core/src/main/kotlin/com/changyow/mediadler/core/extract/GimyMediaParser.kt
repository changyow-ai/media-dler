package com.changyow.mediadler.core.extract

import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.MediaResource
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Reads static player data or the legacy named DPlayer iframe, without executing scripts. */
object GimyMediaParser {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val scripts = Regex("""<script\b[^>]*>(.*?)</script\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val attribute = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val variable = Regex("""\b(?:var|let|const)\s+url\s*=\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')\s*;""")

    fun parsePage(html: String, pageUrl: String, originalUrl: String = pageUrl): MediaItem =
        MacCmsMediaParser.parse(html, pageUrl, originalUrl, SourceKind.GIMY)

    fun playerUrl(html: String, pageUrl: String): String {
        check(GimyUrl.playbackId(pageUrl) != null) { "請分享 Gimy 的單集播放頁網址" }
        val urls = Regex("""<iframe\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { tag ->
            val attrs = attributes(tag.value)
            attrs["src"]?.takeIf { attrs["name"] == "p-frame" }
                ?.let { URI(pageUrl).resolve(htmlText(it)).toString() }
        }.toList()
        check(urls.size == 1 && GimyUrl.isPlayer(urls.single())) {
            "Gimy 播放器資料缺失或已變更，請重新分享單集播放頁"
        }
        return urls.single()
    }

    fun parse(html: String, playerHtml: String, pageUrl: String, playerUrl: String, originalUrl: String = pageUrl): MediaItem {
        val id = GimyUrl.playbackId(pageUrl) ?: error("請分享 Gimy 的單集播放頁網址")
        check(GimyUrl.isPlayer(playerUrl)) { "Gimy 播放器網址不受支援" }
        val urls = scripts.findAll(playerHtml).map { it.groupValues[1] }.filter {
            Regex("""new\s+DPlayer\s*\(""").containsMatchIn(it) &&
                Regex("""video\s*:\s*\{\s*url\s*:\s*url\s*[,}]""").containsMatchIn(it)
        }.flatMap { variable.findAll(it).map { match -> decodeLiteral(match.groupValues[1]) } }.toList()
        check(urls.size == 1) { "Gimy 沒有提供明確的影片網址，請重試或選擇其他播放線路" }
        val media = runCatching { URI(urls.single()) }.getOrNull()?.takeIf {
            it.scheme in setOf("http", "https") && it.host != null && it.rawUserInfo == null &&
                (it.path.orEmpty().lowercase().endsWith(".m3u8") || it.path.orEmpty().lowercase().endsWith(".mp4"))
        }?.toString() ?: error("Gimy 這條線路未提供可下載的 HTTP 影片，請選擇其他播放線路")
        val originalTitle = Regex("""<title\b[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let(::htmlText)?.takeIf { it.isNotBlank() }
        val heading = Regex("""<h1\b[^>]*>(.*?)</h1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)
        val series = heading?.let { Regex("""<a\b[^>]*href=["']/voddetail2/""" + id + """\.html["'][^>]*>(.*?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(it)?.groupValues?.get(1) }
            ?.let(::htmlText)?.removeSuffix("線上看")?.trim()?.takeIf { it.isNotBlank() }
        val episode = heading?.let(::htmlText)?.let { Regex("""第\s*(\d+)\s*[集話]""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val headers = mapOf("Referer" to playerUrl, "User-Agent" to USER_AGENT)
        val resource = MediaResource(media, headers)
        return MediaItem(
            sourceUrl = media, playlistIndex = null, id = "gimy_${URI(pageUrl).path.substringAfterLast('/').removeSuffix(".html")}",
            title = originalTitle ?: series ?: "Gimy_$id", thumbnailUrl = null, durationSeconds = null, isImage = false,
            formats = listOf(MediaFormat("best", "mp4", "最佳畫質", null, true, true, false, null, resource)),
            source = SourceContext(originalUrl, pageUrl, SourceKind.GIMY), downloadTarget = DownloadTarget.DIRECT,
            httpHeaders = headers, originalTitle = originalTitle, seriesTitle = series, episodeNumber = episode,
        )
    }

    private fun attributes(tag: String) = attribute.findAll(tag).associate {
        it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] }
    }

    private fun decodeLiteral(literal: String): String {
        if (literal.startsWith('"')) return Json.parseToJsonElement(literal).jsonPrimitive.content
        val value = literal.substring(1, literal.length - 1)
        val decoded = StringBuilder()
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            if (c != '\\') { decoded.append(c); continue }
            check(index < value.length) { "Gimy 影片網址格式已變更" }
            when (val escaped = value[index++]) {
                '\\', '/', '\'', '"' -> decoded.append(escaped)
                'u', 'x' -> {
                    val count = if (escaped == 'u') 4 else 2
                    val code = value.substring(index, (index + count).coerceAtMost(value.length)).takeIf { it.length == count }?.toIntOrNull(16)
                        ?: error("Gimy 影片網址編碼無法解析")
                    decoded.append(code.toChar()); index += count
                }
                else -> error("Gimy 影片網址編碼尚未支援，請選擇其他播放線路")
            }
        }
        return decoded.toString()
    }

    private fun htmlText(value: String): String = value.replace(Regex("<[^>]*>"), "").replace(Regex("""&#(x[0-9a-fA-F]+|\d+);""")) {
        val n = it.groupValues[1]
        val code = if (n.startsWith('x')) n.substring(1).toIntOrNull(16) else n.toIntOrNull()
        code?.takeIf(Character::isValidCodePoint)?.let { c -> String(Character.toChars(c)) } ?: it.value
    }.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&").trim()
}
