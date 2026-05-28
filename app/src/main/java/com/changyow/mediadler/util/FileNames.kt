package com.changyow.mediadler.util

object FileNames {
    private val ILLEGAL = Regex("""[\\/:*?"<>|\n\r\t]""")

    fun sanitize(name: String, maxLength: Int = 120): String {
        val cleaned = ILLEGAL.replace(name, "_").trim().trim('.')
        return cleaned.take(maxLength).trim().ifBlank { "media" }
    }
}
