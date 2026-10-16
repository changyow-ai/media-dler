package com.changyow.mediadler.core.extract

import java.net.URI
import java.util.Locale

object GimyUrl {
    private val hosts = setOf("gimytv.io", "www.gimytv.io", "gimytw.cc", "www.gimytw.cc")
    private val aiHosts = setOf("gimyai.tw", "www.gimyai.tw")
    private val playback = Regex("""^/eps/(\d+)-([A-Za-z0-9-]+)\.html$""")
    private val player = Regex("""^/_watch/\d+/?$""")
    private val catalog = Regex("""^/voddetail2/(\d+)\.html$""")
    private val aiCatalog = Regex("""^/detail/(\d+)\.html$""")
    private val aiPlayback = Regex("""^/play/(\d+)-(\d+)-(\d+)\.html$""")

    data class PlaybackPage(val id: String, val line: Int, val index: Int)

    fun isSupported(url: String): Boolean = uri(url) != null
    fun isGimyAi(url: String): Boolean = uri(url)?.host?.lowercase(Locale.ROOT) in aiHosts
    fun catalogId(url: String): String? = uri(url)?.let {
        (if (isGimyAi(url)) aiCatalog else catalog).matchEntire(it.rawPath)?.groupValues?.get(1)
    }
    fun playbackId(url: String): String? = if (isGimyAi(url)) playbackOrNull(url)?.id
        else uri(url)?.rawPath?.let { playback.matchEntire(it)?.groupValues?.get(1) }
    fun isPlayer(url: String): Boolean = !isGimyAi(url) && (uri(url)?.rawPath?.let(player::matches) ?: false)

    fun playbackOrNull(url: String): PlaybackPage? {
        if (!isGimyAi(url)) return null
        val match = aiPlayback.matchEntire(uri(url)?.rawPath ?: return null) ?: return null
        val line = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val index = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return PlaybackPage(match.groupValues[1], line, index)
    }

    private fun uri(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && it.rawUserInfo == null &&
            (it.port == -1 || it.port == 443) && it.host?.lowercase(Locale.ROOT).let { host -> host in hosts || host in aiHosts }
    }
}
