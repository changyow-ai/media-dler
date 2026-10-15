package com.changyow.mediadler.core.extract

import com.changyow.mediadler.core.model.CatalogEpisode
import com.changyow.mediadler.core.model.EpisodePage
import com.changyow.mediadler.core.model.PlaybackLine
import com.changyow.mediadler.core.model.SeriesCatalog
import com.changyow.mediadler.core.model.SourceKind
import java.net.URI

/** Scoped to the two observed playlist structures. No page or player scripts are executed. */
object SeriesCatalogParser {
    private val flags = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val attributes = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val anchors = Regex("""<a\b([^>]*)>(.*?)</a\s*>""", flags)
    private val playlistBlocks = Regex("""<div\b([^>]*)>\s*<ul\b([^>]*)>(.*?)</ul\s*>\s*</div\s*>""", flags)
    private val episodeLabel = Regex("""^第\s*(\d+)\s*[集話]$""")
    private val nonEpisodeLabels = setOf("立即播放", "立即觀看", "播放", "下載", "廣告", "广告", "推薦影片", "推荐影片", "更多好片")

    fun isCatalogUrl(url: String): Boolean = EightMapleUrl.catalogId(url) != null || GimyUrl.catalogId(url) != null

    fun parse(html: String, pageUrl: String, originalUrl: String = pageUrl): SeriesCatalog {
        val eightMapleId = EightMapleUrl.catalogId(pageUrl)
        val gimyId = GimyUrl.catalogId(pageUrl)
        check(eightMapleId != null || gimyId != null) { "請分享 Gimy 或 8maple 的影片首頁網址" }
        val clean = html.replace(Regex("""<!--.*?-->""", flags), "")
            .replace(Regex("""<(script|style)\b[^>]*>.*?</\1\s*>""", flags), "")
        val series = Regex("""<h1\b[^>]*>(.*?)</h1\s*>""", flags).find(clean)?.groupValues?.get(1)
            ?.let(::text)?.takeIf { it.isNotBlank() }
        val title = series ?: "未命名影片 ${eightMapleId ?: gimyId}"
        val kind = if (eightMapleId != null) SourceKind.EIGHT_MAPLE else SourceKind.GIMY
        val thumbnail = thumbnail(clean, pageUrl)
        val season = series?.let { Regex("""第\s*(\d+)\s*季""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        fun episodes(body: String, lineId: String, lineName: String, allowed: (String) -> Boolean): List<CatalogEpisode> {
            check(!Regex("""<ul\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)) { "播放清單結構已變更，請稍後重試" }
            val result = linkedMapOf<String, CatalogEpisode>()
            anchors.findAll(body).forEachIndexed { order, link ->
                val href = attrs(link.groupValues[1])["href"] ?: return@forEachIndexed
                val url = resolve(pageUrl, href)?.takeIf(allowed) ?: return@forEachIndexed
                val label = text(link.groupValues[2]).takeIf { it.isNotBlank() } ?: return@forEachIndexed
                if (label in nonEpisodeLabels) return@forEachIndexed
                val number = episodeLabel.matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
                val key = URI(url).path
                val previous = result[key]
                if (previous != null) {
                    check(previous.label == label || number != null && previous.page.episodeNumber == number) { "播放清單有重複且不一致的集數，請重新整理首頁" }
                    return@forEachIndexed
                }
                check(number == null || result.values.none { it.page.episodeNumber == number }) { "同一線路有不明確的重複集數，請選擇其他線路" }
                result[key] = CatalogEpisode(label, EpisodePage(
                    playbackUrl = url, kind = kind, originalUrl = url, catalogUrl = pageUrl,
                    lineId = lineId, lineName = lineName, title = "$title $label", seriesTitle = series,
                    seasonNumber = season, episodeNumber = number, thumbnailUrl = thumbnail,
                ), order)
            }
            return result.values.toList()
        }

        val lines = if (eightMapleId != null) {
            val tabs = linkedMapOf<String, String>()
            anchors.findAll(clean).forEach { link ->
                val attributes = attrs(link.groupValues[1])
                if (attributes["data-toggle"] != "tab") return@forEach
                val id = Regex("""#playlist(\d+)""").matchEntire(attributes["href"].orEmpty())?.groupValues?.get(1) ?: return@forEach
                check(id.toIntOrNull()?.let { it > 0 } == true) { "8maple 線路編號無法辨識" }
                val name = text(link.groupValues[2]).ifBlank { "線路 $id" }
                check(tabs[id] == null || tabs[id] == name) { "8maple 線路名稱重複且不一致" }
                tabs[id] = name
            }
            val blocks = playlistBlocks.findAll(clean).filter { attrs(it.groupValues[1])["id"]?.matches(Regex("playlist\\d+")) == true }.toList()
            val byId = blocks.groupBy { attrs(it.groupValues[1]).getValue("id") }
            check(tabs.isNotEmpty() && byId.values.all { it.size == 1 }) { "8maple 播放線路資料缺失或不明確" }
            tabs.entries.mapIndexed { order, (id, name) ->
                val block = byId["playlist$id"]?.singleOrNull() ?: error("8maple 的 $name 清單缺失，請重新整理首頁")
                check("myui-content__list" in attrs(block.groupValues[2])["class"].orEmpty().split(Regex("\\s+"))) { "8maple 播放清單結構已變更" }
                PlaybackLine(id, name, order, episodes(block.groupValues[3], id, name) { url ->
                    EightMapleUrl.playbackOrNull(url)?.let { it.id == eightMapleId && it.line.toString() == id } == true
                })
            }
        } else {
            // This Gimy catalog exposes a single default player list, not a mapping from
            // con_playlist_N to an iframe line. Never guess that mapping if the site changes.
            val blocks = playlistBlocks.findAll(clean).filter {
                "playlist" in attrs(it.groupValues[1])["class"].orEmpty().split(Regex("\\s+")) &&
                    attrs(it.groupValues[2])["id"]?.matches(Regex("con_playlist_\\d+")) == true
            }.toList()
            check(blocks.size == 1) { "Gimy 首頁播放清單缺失或線路結構已變更，請分享單集播放頁" }
            val header = Regex("""<div\b[^>]*class=["'][^"']*details-play-title[^"']*["'][^>]*>(.*?)</div\s*>""", flags)
                .find(clean)?.groupValues?.get(1)
            val name = header?.let { anchors.find(it)?.groupValues?.get(2)?.let(::text) }?.takeIf { it.isNotBlank() } ?: "在線播放"
            listOf(PlaybackLine("default", name, 0, episodes(blocks.single().groupValues[3], "default", name) { GimyUrl.playbackId(it) == gimyId }))
        }
        check(lines.any { it.episodes.isNotEmpty() }) { "這個影片首頁目前沒有可選集數，請稍後重試" }
        return SeriesCatalog(originalUrl, pageUrl, kind, title, thumbnail, lines)
    }

    private fun thumbnail(html: String, base: String): String? {
        val og = Regex("""<meta\b[^>]*>""", flags).findAll(html).map { attrs(it.value) }
            .firstOrNull { it["property"] == "og:image" }?.get("content")
        val poster = Regex("""<(?:span|div)\b[^>]*>""", flags).findAll(html).map { attrs(it.value) }
            .firstOrNull { "video-pic" in it["class"].orEmpty().split(Regex("\\s+")) }?.get("style")
            ?.let { Regex("""url\(\s*['"]?([^)'"\s]+)['"]?\s*\)""").find(it)?.groupValues?.get(1) }
        return (og ?: poster)?.let { resolve(base, it) }
    }

    private fun attrs(value: String): Map<String, String> = attributes.findAll(value).associate {
        it.groupValues[1].lowercase() to text(it.groupValues[2].ifEmpty { it.groupValues[3] })
    }
    private fun resolve(base: String, value: String): String? = runCatching { URI(base).resolve(value) }.getOrNull()?.takeIf {
        it.scheme in setOf("https", "http") && it.host != null && it.rawUserInfo == null
    }?.toString()
    private fun text(value: String): String = value.replace(Regex("<[^>]*>"), "").replace(Regex("""&#(x[0-9a-fA-F]+|\d+);""")) {
        val n = it.groupValues[1]
        val code = if (n.startsWith('x')) n.substring(1).toIntOrNull(16) else n.toIntOrNull()
        code?.takeIf(Character::isValidCodePoint)?.let { c -> String(Character.toChars(c)) } ?: it.value
    }.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&").trim()
}
