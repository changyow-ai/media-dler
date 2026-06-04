package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.LanguageDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageDecisionTest {
    @Test fun chineseGetsS2twp() {
        assertEquals("s2twp", LanguageDecision.openCcConfig("zh"))
        assertEquals("s2twp", LanguageDecision.openCcConfig("ZH"))
        assertEquals("s2twp", LanguageDecision.openCcConfig("zh-CN"))
        assertEquals("s2twp", LanguageDecision.openCcConfig("zh_TW"))
        assertEquals("s2twp", LanguageDecision.openCcConfig("Chinese"))
        assertEquals("s2twp", LanguageDecision.openCcConfig("  zh  "))
    }

    @Test fun nonChineseUntouched() {
        assertNull(LanguageDecision.openCcConfig("en"))
        assertNull(LanguageDecision.openCcConfig("ja"))
        assertNull(LanguageDecision.openCcConfig(""))
        assertNull(LanguageDecision.openCcConfig(null))
    }
}
