package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.download.HlsResumeGuard

/** Only the provider's explicit v1 marker authorizes skipping a complete plain-TS block. */
internal object EightMapleHlsAds {
    const val IDENTITY_VERSION = "eightmaple-provider-ad-filter-v1"
    private const val MARKER = "# ppvod-ad-injected v1"
    private const val DISCONTINUITY = "#EXT-X-DISCONTINUITY"
    private const val MAX_BLOCKS = 16
    private const val MAX_BLOCK_SEGMENTS = 128
    private const val MAX_BLOCK_SECONDS = 600.0

    data class Result(
        val manifest: String,
        val skippedAds: Int = 0,
        val skippedAdSegments: Int = 0,
        val skippedAdSeconds: Double = 0.0,
    )

    /** Any unsupported or malformed block rolls back the entire original snapshot. */
    fun filter(manifest: String): Result {
        val unchanged = Result(manifest)
        if (manifest.length > 1024 * 1024 || HlsResumeGuard.fingerprint(manifest) == null) return unchanged
        val lines = Regex("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").findAll(manifest)
            .filter { it.value.isNotEmpty() }
            .map { Line(it.value.trimEnd('\r', '\n'), it.range.first) }.toList()
        val markers = lines.filter { it.text.trimStart().startsWith('#') &&
            it.text.contains("ppvod-ad-injected", ignoreCase = true) }
        if (markers.isEmpty() || markers.size > MAX_BLOCKS || markers.any { it.text != MARKER }) return unchanged

        val removedRanges = mutableListOf<IntRange>()
        var index = 0
        var pendingSegment = false
        var skippedSegments = 0
        var skippedSeconds = 0.0
        while (index < lines.size) {
            val line = lines[index]
            if (line.text != MARKER) {
                val text = line.text.trim()
                if (text.startsWith("#EXTINF:")) pendingSegment = true
                else if (text.isNotEmpty() && !text.startsWith('#')) pendingSegment = false
                index++
                continue
            }
            if (pendingSegment || lines.getOrNull(index + 1)?.text != DISCONTINUITY) return unchanged
            var cursor = index + 2
            var segments = 0
            var seconds = 0.0
            while (lines.getOrNull(cursor)?.text != DISCONTINUITY) {
                val durationLine = lines.getOrNull(cursor)?.text ?: return unchanged
                val uri = lines.getOrNull(cursor + 1)?.text?.trim() ?: return unchanged
                if (!durationLine.startsWith("#EXTINF:") || uri.isEmpty() || uri.startsWith('#')) return unchanged
                val duration = durationLine.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: return unchanged
                if (!duration.isFinite() || duration <= 0) return unchanged
                seconds += duration
                segments++
                if (segments > MAX_BLOCK_SEGMENTS || seconds > MAX_BLOCK_SECONDS) return unchanged
                cursor += 2
            }
            if (segments == 0) return unchanged
            // Keep the closing discontinuity verbatim as the splice into retained media.
            removedRanges += line.start until lines[cursor].start
            skippedSegments += segments
            skippedSeconds += seconds
            index = cursor + 1
        }
        val filtered = buildString {
            var retainedStart = 0
            removedRanges.forEach { range ->
                append(manifest, retainedStart, range.first)
                retainedStart = range.last + 1
            }
            append(manifest, retainedStart, manifest.length)
        }
        if (HlsResumeGuard.fingerprint(filtered) == null) return unchanged
        return Result(filtered, removedRanges.size, skippedSegments, skippedSeconds)
    }

    private data class Line(val text: String, val start: Int)
}
