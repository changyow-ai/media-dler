package com.changyow.mediadler.core.extract

import java.net.URI
import java.util.Locale

object GimyUrl {
    private val hosts = setOf("gimytv.io", "www.gimytv.io", "gimytw.cc", "www.gimytw.cc")
    private val playback = Regex("""^/eps/(\d+)-([A-Za-z0-9-]+)\.html$""")
    private val player = Regex("""^/_watch/\d+/?$""")
    private val catalog = Regex("""^/voddetail2/(\d+)\.html$""")

    fun isSupported(url: String): Boolean = uri(url) != null
    fun catalogId(url: String): String? = uri(url)?.rawPath?.let { catalog.matchEntire(it)?.groupValues?.get(1) }
    fun playbackId(url: String): String? = uri(url)?.rawPath?.let { playback.matchEntire(it)?.groupValues?.get(1) }
    fun isPlayer(url: String): Boolean = uri(url)?.rawPath?.let(player::matches) ?: false

    private fun uri(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && it.rawUserInfo == null &&
            (it.port == -1 || it.port == 443) && it.host?.lowercase(Locale.ROOT) in hosts
    }
}
