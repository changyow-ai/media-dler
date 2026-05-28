package com.changyow.mediadler.core.download

import com.changyow.mediadler.core.model.FormatSelection

object FormatSelector {
    /** yt-dlp CLI arguments (format selection / audio extraction) for a [selection]. */
    fun args(selection: FormatSelection): List<String> = when (selection) {
        FormatSelection.ImageOriginal -> emptyList()
        FormatSelection.BestVideo -> listOf("-f", "bv*+ba/b")
        is FormatSelection.CappedVideo -> {
            val h = selection.maxHeight
            listOf("-f", "bv*[height<=$h]+ba/b[height<=$h]/b")
        }
        is FormatSelection.Audio ->
            listOf("-x", "--audio-format", selection.audioFormat.ext, "-f", "ba/b")
        is FormatSelection.SpecificFormat -> {
            val f = selection.format
            val sel = if (f.hasAudio || f.isImage) f.formatId else "${f.formatId}+ba/${f.formatId}/b"
            listOf("-f", sel)
        }
    }
}
