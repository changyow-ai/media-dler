package com.changyow.mediadler.util

import java.io.File
import java.net.URLConnection

object MimeTypes {
    /** Direct CDN image URLs may have no extension, yielding yt-dlp's `unknown_video`. */
    fun imageExtensionFromContent(file: File): String? = file.inputStream().buffered().use { input ->
        input.mark(32)
        val header = ByteArray(12)
        val count = input.read(header)
        input.reset()
        if (count == 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) return@use "webp"
        when (URLConnection.guessContentTypeFromStream(input)) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/bmp", "image/x-bmp" -> "bmp"
            else -> null
        }
    }

    fun fromExtension(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }
}
