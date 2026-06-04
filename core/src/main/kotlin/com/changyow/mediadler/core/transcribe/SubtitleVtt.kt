package com.changyow.mediadler.core.transcribe

/**
 * Converts WebVTT subtitle text into a plain transcript: strips the header/NOTE/cue-index and
 * timestamp lines, removes inline tags (`<c>`, `<00:00:00.000>`, …), and collapses consecutive
 * duplicate lines (YouTube auto-captions repeat the previous rolling line). Used by the YouTube CC
 * shortcut so a video that already has captions skips the transcription engine entirely.
 *
 * Header keywords (WEBVTT/Kind:/Language:/STYLE/REGION) are only stripped in the header region
 * before the first cue, and an all-digit line is treated as a cue index only when the very next
 * line is its timestamp — so a caption whose text is itself a number (a year like "2024", a count)
 * or that happens to begin with a header keyword is kept rather than silently dropped.
 */
object SubtitleVtt {
    private val TAG = Regex("<[^>]*>")

    fun toPlainText(vtt: String): String {
        val lines = vtt.lineSequence().map { it.trim() }.toList()
        val out = ArrayList<String>()
        var seenCue = false // flipped on the first timestamp line; before it we are in the header
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isEmpty()) continue
            if (line.contains("-->")) { seenCue = true; continue } // timestamp / cue-settings line
            if (isNote(line)) continue                              // NOTE block (can appear anywhere)
            if (!seenCue && isHeaderKeyword(line)) continue         // metadata, only in the header
            // A standalone all-digit line is a cue index only if its very next line is the timestamp.
            if (line.all { it.isDigit() } && lines.getOrNull(i + 1)?.contains("-->") == true) continue
            val text = TAG.replace(line, "").trim()
            if (text.isEmpty()) continue
            if (out.isEmpty() || out.last() != text) out.add(text)
        }
        return out.joinToString("\n")
    }

    private fun isNote(line: String): Boolean = line == "NOTE" || line.startsWith("NOTE ")

    private fun isHeaderKeyword(line: String): Boolean =
        line.startsWith("WEBVTT") || line.startsWith("Kind:") || line.startsWith("Language:") ||
            line == "STYLE" || line == "REGION"
}
