package com.changyow.mediadler.core.transcribe

/**
 * Decides post-processing for a detected language. The only transform today is normalising Chinese
 * to Taiwan traditional via OpenCC `s2twp` (matching the sister `whisper` desktop pipeline); the
 * actual conversion runs in :app, :core only picks the config string.
 */
object LanguageDecision {
    /** OpenCC config to apply for [detectedLanguage], or null to leave the text untouched. */
    fun openCcConfig(detectedLanguage: String?): String? {
        val lang = detectedLanguage?.trim()?.lowercase() ?: return null
        val isChinese = lang == "zh" || lang.startsWith("zh-") || lang.startsWith("zh_") || lang == "chinese"
        return if (isChinese) "s2twp" else null
    }
}
