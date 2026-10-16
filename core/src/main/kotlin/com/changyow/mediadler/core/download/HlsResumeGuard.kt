package com.changyow.mediadler.core.download

import java.net.URI
import java.security.MessageDigest

/** Only identical, complete, plain TS playlists may reuse yt-dlp's fragment journal. */
object HlsResumeGuard {
    fun fingerprint(manifest: String): String? {
        val lines = lines(manifest)
        if (lines.firstOrNull() != "#EXTM3U" || lines.lastOrNull() != "#EXT-X-ENDLIST") return null
        var pendingSegment = false
        var segments = 0
        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXTINF:") -> {
                    if (pendingSegment) return null
                    val duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    if (duration == null || !duration.isFinite() || duration <= 0) return null
                    pendingSegment = true
                }
                line == "#EXT-X-ENDLIST" -> if (pendingSegment) return null
                line == "#EXT-X-DISCONTINUITY" || line == "#EXT-X-INDEPENDENT-SEGMENTS" ||
                    line == "#EXT-X-KEY:METHOD=NONE" || line == "#EXT-X-PLAYLIST-TYPE:VOD" -> Unit
                line.substringBefore(':') in mediaMetadata -> Unit
                line.startsWith("#EXT") || line.startsWith("#ANVATO") || line.startsWith("#UPLYNK") -> return null
                line.startsWith('#') -> Unit
                else -> {
                    if (!pendingSegment || !isTsReference(line)) return null
                    pendingSegment = false
                    segments++
                }
            }
        }
        if (segments == 0 || pendingSegment) return null
        return MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Resolves exactly one ordinary variant; alternate renditions and nested masters stay unsupported. */
    fun singleVariantUrl(manifest: String, baseUrl: String): String? {
        val lines = lines(manifest)
        if (lines.firstOrNull() != "#EXTM3U") return null
        var variant: String? = null
        var expectingUri = false
        for (line in lines.drop(1)) {
            when {
                expectingUri -> {
                    if (line.startsWith('#')) return null
                    variant = line
                    expectingUri = false
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    if (variant != null || renditionAttribute.containsMatchIn(line)) return null
                    expectingUri = true
                }
                line.startsWith("#EXT-X-VERSION:") || line == "#EXT-X-INDEPENDENT-SEGMENTS" -> Unit
                line.startsWith("#EXT") || !line.startsWith('#') -> return null
            }
        }
        if (expectingUri || variant == null) return null
        return try {
            val base = URI(baseUrl)
            val resolved = base.resolve(URI(variant))
            if (!isHttpUrl(base) || !isHttpUrl(resolved)) null else resolved.toASCIIString()
        } catch (_: Exception) { null }
    }

    private fun lines(manifest: String) = manifest.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

    private fun isTsReference(value: String): Boolean = try {
        val uri = URI(value)
        uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.scheme == null || isHttpUrl(uri)) &&
            (uri.rawAuthority == null || uri.host != null) && uri.path.orEmpty().endsWith(".ts", ignoreCase = true)
    } catch (_: Exception) { false }

    private fun isHttpUrl(uri: URI) = uri.scheme?.lowercase() in setOf("http", "https") &&
        uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null

    private val mediaMetadata = setOf("#EXT-X-VERSION", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-DISCONTINUITY-SEQUENCE", "#EXT-X-ALLOW-CACHE")
    private val renditionAttribute = Regex("(?:[:,])\\s*(?:AUDIO|VIDEO|SUBTITLES|CLOSED-CAPTIONS|URI)=")
}
