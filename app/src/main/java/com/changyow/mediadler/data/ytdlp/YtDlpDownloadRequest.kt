package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.download.DownloadPlan
import com.yausername.youtubedl_android.YoutubeDLRequest

/** Preserve every required header as a separate CLI argument. */
internal fun DownloadPlan.toYoutubeDLRequest(downloadRateLimitBytesPerSecond: Long? = null): YoutubeDLRequest =
    YoutubeDLRequest(resource.url).apply {
        if (playlistIndex != null) {
            addOption("--playlist-items", playlistIndex.toString())
        } else {
            addOption("--no-playlist")
        }
        addCommands(resource.httpHeaders.flatMap { (name, value) -> listOf("--add-headers", "$name:$value") })
        addCommands(formatArguments)
        if (downloadRateLimitBytesPerSecond != null) {
            require(downloadRateLimitBytesPerSecond > 0) { "Download rate must be positive" }
            // Pass exact bytes: engine K/M suffixes use binary units, while the UI uses SI KB/MB.
            addOption("--limit-rate", downloadRateLimitBytesPerSecond.toString())
        }
    }
