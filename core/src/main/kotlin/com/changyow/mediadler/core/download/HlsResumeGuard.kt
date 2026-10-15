package com.changyow.mediadler.core.download

import java.security.MessageDigest

/** Only identical, complete, unencrypted media playlists may reuse yt-dlp's fragment journal. */
object HlsResumeGuard {
    fun fingerprint(manifest: String): String? {
        val lines = manifest.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != "#EXTM3U" || "#EXT-X-ENDLIST" !in lines ||
            lines.none { it.startsWith("#EXTINF:") } ||
            lines.any { it.startsWith("#EXT-X-STREAM-INF:") || it.startsWith("#EXT-X-MEDIA:") ||
                it.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ||
                it.startsWith("#EXT-X-KEY:") && it != "#EXT-X-KEY:METHOD=NONE" ||
                it.startsWith("#EXT-X-MAP:") || it.startsWith("#EXT-X-PART:") }
        ) return null
        return MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
