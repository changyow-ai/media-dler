package com.changyow.mediadler.transcribe

import com.changyow.mediadler.core.transcribe.LanguageDecision
import com.github.houbb.opencc4j.util.ZhConverterUtil

/**
 * Applies the OpenCC conversion that [LanguageDecision] selected. Today that is Chinese →
 * traditional. opencc4j does character-level (plus some phrase) s2t; full Taiwan-phrase `s2twp`
 * localisation (e.g. 軟件→軟體) is a later refinement with bundled OpenCC dictionaries.
 */
object OpenCcConverter {
    fun normalize(text: String, detectedLanguage: String?): String {
        if (text.isBlank()) return text
        return when (LanguageDecision.openCcConfig(detectedLanguage)) {
            "s2twp" -> ZhConverterUtil.toTraditional(text)
            else -> text
        }
    }
}
