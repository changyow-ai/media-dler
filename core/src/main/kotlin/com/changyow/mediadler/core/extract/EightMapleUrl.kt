package com.changyow.mediadler.core.extract

import java.net.URI
import java.util.Locale

object EightMapleUrl {
    private val hosts = setOf("8maple.st", "www.8maple.st", "play.8maple.st")
    private val playbackPath = Regex("""^/play/([A-Za-z0-9]+)-(\d+)-(\d+)\.html$""")
    private val catalogPath = Regex("""^/video/([A-Za-z0-9]+)\.html$""")

    data class PlaybackPage(val id: String, val line: Int, val index: Int)

    fun isSupported(url: String): Boolean = uri(url) != null
    fun catalogId(url: String): String? = uri(url)?.rawPath?.let { catalogPath.matchEntire(it)?.groupValues?.get(1) }

    fun playbackOrNull(url: String): PlaybackPage? {
        val match = playbackPath.matchEntire(uri(url)?.rawPath ?: return null) ?: return null
        val line = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val index = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return PlaybackPage(match.groupValues[1], line, index)
    }

    private fun uri(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && it.rawUserInfo == null &&
            (it.port == -1 || it.port == 443) && it.host?.lowercase(Locale.ROOT) in hosts
    }
}
