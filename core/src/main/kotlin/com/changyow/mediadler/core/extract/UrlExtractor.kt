package com.changyow.mediadler.core.extract

object UrlExtractor {
    private val URL = Regex("""https?://[^\s<>"'\\]+""")
    private const val TRAILING = ".,;:!?)]}>\"'"

    /** Returns the first http(s) URL in [text], stripped of trailing punctuation, or null. */
    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val raw = URL.find(text)?.value ?: return null
        return raw.trimEnd { it in TRAILING }.ifBlank { null }
    }
}
