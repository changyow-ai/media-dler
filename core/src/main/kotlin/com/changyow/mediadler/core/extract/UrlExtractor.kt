package com.changyow.mediadler.core.extract

object UrlExtractor {
    private val URL = Regex("""https?://[^\s<>"'\\]+""")
    private const val SENTENCE_TRAILING = ".,;:!?\""

    /** Returns the first http(s) URL in [text], stripped of trailing prose punctuation, or null. */
    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        var url = URL.find(text)?.value ?: return null
        url = url.trimEnd { it in SENTENCE_TRAILING }
        // Strip a trailing bracket only when it is unbalanced (belongs to the
        // surrounding sentence), so paths like /wiki/Foo_(bar) stay intact.
        while (url.isNotEmpty()) {
            val last = url.last()
            val unbalanced = when (last) {
                ')' -> url.count { it == '(' } < url.count { it == ')' }
                ']' -> url.count { it == '[' } < url.count { it == ']' }
                '}' -> url.count { it == '{' } < url.count { it == '}' }
                else -> false
            }
            if (unbalanced) url = url.dropLast(1) else break
        }
        return url.ifBlank { null }
    }
}
