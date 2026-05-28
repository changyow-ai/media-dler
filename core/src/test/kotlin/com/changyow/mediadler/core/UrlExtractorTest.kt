package com.changyow.mediadler.core

import com.changyow.mediadler.core.extract.UrlExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlExtractorTest {
    @Test fun plainUrl() {
        assertEquals("https://youtu.be/abc", UrlExtractor.firstUrl("https://youtu.be/abc"))
    }

    @Test fun urlEmbeddedInText() {
        assertEquals(
            "https://www.instagram.com/p/XYZ/",
            UrlExtractor.firstUrl("看這個 https://www.instagram.com/p/XYZ/ 很酷"),
        )
    }

    @Test fun stripsTrailingPunctuation() {
        assertEquals("https://x.com/a/123", UrlExtractor.firstUrl("see https://x.com/a/123)."))
    }

    @Test fun takesFirstOfMany() {
        assertEquals("https://one.com", UrlExtractor.firstUrl("a https://one.com b https://two.com"))
    }

    @Test fun nullWhenNone() {
        assertNull(UrlExtractor.firstUrl("no link here"))
        assertNull(UrlExtractor.firstUrl(null))
        assertNull(UrlExtractor.firstUrl(""))
    }

    @Test fun keepsBalancedParensInPath() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Python_(programming_language)",
            UrlExtractor.firstUrl("https://en.wikipedia.org/wiki/Python_(programming_language)"),
        )
    }

    @Test fun keepsBalancedParensButDropsSentencePeriod() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Python_(programming_language)",
            UrlExtractor.firstUrl("read https://en.wikipedia.org/wiki/Python_(programming_language)."),
        )
    }
}
