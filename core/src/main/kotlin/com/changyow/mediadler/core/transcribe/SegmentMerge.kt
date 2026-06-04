package com.changyow.mediadler.core.transcribe

/**
 * Joins per-window transcripts back into one text. Because [WindowPlanner] windows overlap, the
 * tail of one window and the head of the next transcribe the same speech; we drop the longest
 * matching seam so it is not duplicated. When no seam is found the parts are joined — with a space
 * for spaced scripts (Latin, digits) but with nothing between two CJK characters, since Chinese /
 * Japanese have no inter-word spacing and a wedged space corrupts the primary-language output.
 */
object SegmentMerge {
    private const val MIN_SEAM = 4     // Latin: ignore trivially short coincidental matches
    private const val MIN_SEAM_CJK = 2 // CJK has no word spacing, so a 2-char overlap is meaningful
    private const val MAX_SEAM = 400   // cap the suffix/prefix scan for cost

    fun merge(parts: List<String>): String {
        val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        val sb = StringBuilder(cleaned.first())
        for (i in 1 until cleaned.size) {
            val next = cleaned[i]
            val seam = longestSeam(sb, next)
            if (seam == 0) {
                if (sb.isNotEmpty() && needsSpace(sb.last(), next.first())) sb.append(' ')
                sb.append(next)
            } else {
                sb.append(next, seam, next.length)
            }
        }
        return sb.toString()
    }

    /** Length of the longest suffix of [acc] that is also a prefix of [next], within the cap. */
    private fun longestSeam(acc: CharSequence, next: String): Int {
        val max = minOf(acc.length, next.length, MAX_SEAM)
        // CJK overlaps are short (no word boundaries), so allow a shorter seam when the join is CJK.
        val min = if (acc.isNotEmpty() && isCjk(acc.last())) MIN_SEAM_CJK else MIN_SEAM
        for (len in max downTo min) {
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

    /** A word-separating space belongs between two spaced-script chars, not between CJK characters. */
    private fun needsSpace(left: Char, right: Char): Boolean = !(isCjk(left) && isCjk(right))

    /** True for scripts written without inter-word spaces (CJK ideographs, kana, Hangul, CJK punctuation). */
    private fun isCjk(c: Char): Boolean = when (Character.UnicodeBlock.of(c)) {
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        -> true
        else -> false
    }
}
