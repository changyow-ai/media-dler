package com.changyow.mediadler.core.download

import com.changyow.mediadler.core.model.FormatSelection

object FormatSelector {
    /** yt-dlp CLI arguments (format selection / audio extraction) for a [selection]. */
    fun args(selection: FormatSelection): List<String> = when (selection) {
        FormatSelection.ImageOriginal -> emptyList()
        FormatSelection.BestVideo -> listOf("-f", "bv*+ba/b")
        is FormatSelection.CappedVideo -> {
            val h = selection.maxHeight
            // Prefer best <= cap; otherwise fall back to an uncapped video+audio
            // merge. DASH-only sites (e.g. Bilibili) have no muxed "b", so ending
            // in "/b" alone errors with "Requested format is not available".
            // -S res:H then picks the resolution closest to the cap.
            listOf("-f", "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b", "-S", "res:$h")
        }
        is FormatSelection.Audio ->
            listOf("-x", "--audio-format", selection.audioFormat.ext, "-f", "ba/b")
        is FormatSelection.SpecificFormat -> {
            val f = selection.format
            // For a video-only stream, merge best audio; never fall back to the
            // muted video-only stream — go straight to best combined.
            val sel = if (f.hasAudio || f.isImage) f.formatId else "${f.formatId}+ba/b"
            listOf("-f", sel)
        }
    }
}
