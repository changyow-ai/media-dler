package com.changyow.mediadler.core.transcribe

/**
 * Joins per-window transcripts back into one text. Because [WindowPlanner] windows overlap, the
 * tail of one window and the head of the next transcribe the same speech; we drop the longest
 * matching seam so it is not duplicated. When no seam is found the parts are joined with a space.
 */
object SegmentMerge {
    private const val MIN_SEAM = 4   // ignore trivially short coincidental matches
    private const val MAX_SEAM = 400 // cap the suffix/prefix scan for cost

    fun merge(parts: List<String>): String {
        val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        val sb = StringBuilder(cleaned.first())
        for (i in 1 until cleaned.size) {
            val next = cleaned[i]
            val seam = longestSeam(sb, next)
            if (seam == 0) {
                sb.append(' ').append(next)
            } else {
                sb.append(next, seam, next.length)
            }
        }
        return sb.toString()
    }

    /** Length of the longest suffix of [acc] that is also a prefix of [next], within the cap. */
    private fun longestSeam(acc: CharSequence, next: String): Int {
        val max = minOf(acc.length, next.length, MAX_SEAM)
        for (len in max downTo MIN_SEAM) {
            if (suffixMatchesPrefix(acc, next, len)) return len
        }
        return 0
    }

    private fun suffixMatchesPrefix(acc: CharSequence, next: String, len: Int): Boolean {
        val offset = acc.length - len
        for (k in 0 until len) {
            if (acc[offset + k] != next[k]) return false
        }
        return true
    }
}
