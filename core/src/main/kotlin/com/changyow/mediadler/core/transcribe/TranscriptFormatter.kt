package com.changyow.mediadler.core.transcribe

/**
 * Turns a raw transcript (already overlap-merged by [SegmentMerge] and OpenCC-normalised) into a
 * readable, line-broken form for DISPLAY / copy / share only. The raw text is left untouched as the
 * source of truth — newlines must never enter it, or the resume/[SegmentMerge] seam dedup (which
 * relies on exact suffix==prefix matching) would break.
 *
 * Two passes, deterministic, no model:
 *  1. Hard break after sentence-terminal punctuation (CJK 。！？；…, or ASCII .!? when clearly
 *     sentence-final), keeping any trailing closing quote/bracket on the same line.
 *  2. Soft-wrap any still-long run with no terminal at ~[SOFT_WRAP] chars, preferring a recent
 *     clause separator (，、；：) — without ever fabricating a period.
 *
 * whisper's Chinese (base/small) often emits no punctuation at all; then only the soft wrap applies,
 * which yields readable blocks rather than true sentences. That is intentional — we never invent
 * sentence boundaries that the model did not produce.
 */
object TranscriptFormatter {

    /** CJK full-width sentence terminals — always a hard break. */
    private val CJK_TERMINALS = setOf('。', '！', '？', '；', '…', '‼', '⁇', '⁈', '⁉')

    /** Closing marks that belong to the sentence just ended, so the break goes after them. */
    private val CLOSERS = setOf('」', '』', '”', '’', '）', ')', ']', '】', '》', '〉', '"', '\'')

    /** Preferred soft-wrap break points inside an over-long unpunctuated run. */
    private val SOFT_BREAKS = setOf('，', '、', '；', '：', ',', ';', ':')

    /** Target line length (counted in chars) before a soft wrap is considered. */
    private const val SOFT_WRAP = 42

    /** Never soft-wrap into a fragment shorter than this (avoids choppy 1–2 char lines). */
    private const val SOFT_WRAP_MIN = 12

    fun format(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""

        val lines = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            val end = terminalGroupEnd(text, i)
            if (end > i) {
                for (k in i until end) cur.append(text[k])
                flush(cur, lines)
                // Drop the run of spaces that followed the terminal (the newline replaces them).
                i = end
                while (i < text.length && text[i] == ' ') i++
            } else {
                cur.append(text[i])
                i++
            }
        }
        flush(cur, lines)

        return lines.flatMap { softWrap(it) }.joinToString("\n")
    }

    /**
     * If a sentence terminal (plus any repeated terminals and trailing closers) starts at [i],
     * returns the index just past that group; otherwise returns [i]. ASCII .!? only count when the
     * char before is not a digit and the group is followed by whitespace, end, or a CJK char — so
     * `3.14`, `v1.2.3` and `example.com` are not split.
     */
    private fun terminalGroupEnd(text: String, i: Int): Int {
        val c = text[i]
        if (c in CJK_TERMINALS) {
            var j = i + 1
            while (j < text.length && text[j] in CJK_TERMINALS) j++
            while (j < text.length && text[j] in CLOSERS) j++
            return j
        }
        if (c == '.' || c == '!' || c == '?') {
            if (c == '.' && i > 0 && text[i - 1].isDigit()) return i
            var j = i + 1
            while (j < text.length && (text[j] == '.' || text[j] == '!' || text[j] == '?')) j++
            val next = if (j < text.length) text[j] else ' '
            val sentenceFinal = j >= text.length || next == ' ' || next == '\n' || isCjk(next)
            if (!sentenceFinal) return i
            while (j < text.length && text[j] in CLOSERS) j++
            return j
        }
        return i
    }

    private fun flush(cur: StringBuilder, out: MutableList<String>) {
        val line = cur.toString().trim()
        if (line.isNotEmpty()) out.add(line)
        cur.setLength(0)
    }

    /**
     * Breaks one over-long terminal-free line into chunks near [SOFT_WRAP] chars, cutting after the
     * last clause separator within reach, else after the last CJK char (never mid Latin word).
     */
    private fun softWrap(line: String): List<String> {
        if (line.length <= SOFT_WRAP) return listOf(line)
        val out = ArrayList<String>()
        var start = 0
        while (line.length - start > SOFT_WRAP) {
            val hardEnd = start + SOFT_WRAP
            var cut = -1
            // Prefer a clause separator in the back half of the window.
            for (k in hardEnd downTo start + SOFT_WRAP_MIN) {
                if (line[k - 1] in SOFT_BREAKS) { cut = k; break }
            }
            // Else cut after the last CJK char so we never split a Latin word.
            if (cut < 0) {
                for (k in hardEnd downTo start + SOFT_WRAP_MIN) {
                    if (isCjk(line[k - 1])) { cut = k; break }
                }
            }
            if (cut < 0) cut = hardEnd
            out.add(line.substring(start, cut).trim())
            start = cut
        }
        if (start < line.length) out.add(line.substring(start).trim())
        return out.filter { it.isNotEmpty() }
    }

    private fun isCjk(c: Char): Boolean = when (Character.UnicodeBlock.of(c)) {
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        -> true
        else -> false
    }
}
