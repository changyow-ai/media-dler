package com.changyow.mediadler.core.transcribe

/**
 * Converts WebVTT subtitle text into a plain transcript: strips the header/NOTE/cue-index and
 * timestamp lines, removes inline tags (`<c>`, `<00:00:00.000>`, …), and collapses consecutive
 * duplicate lines (YouTube auto-captions repeat the previous rolling line). Used by the YouTube CC
 * shortcut so a video that already has captions skips the transcription engine entirely.
 */
object SubtitleVtt {
    private val TAG = Regex("<[^>]*>")

    fun toPlainText(vtt: String): String {
        val out = ArrayList<String>()
        for (raw in vtt.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("WEBVTT")) continue
            if (line.startsWith("NOTE")) continue
            if (line.startsWith("Kind:") || line.startsWith("Language:")) continue
            if (line.contains("-->")) continue           // timestamp / cue-settings line
            if (line.all { it.isDigit() }) continue       // standalone cue index
            val text = TAG.replace(line, "").trim()
            if (text.isEmpty()) continue
            if (out.isEmpty() || out.last() != text) out.add(text)
        }
        return out.joinToString("\n")
    }
}
