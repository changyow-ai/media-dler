package com.changyow.mediadler.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals

class SherpaTranscriptMergeTest {
    @Test fun innerSliceOverlapMatchesAcrossChineseScripts() {
        assertEquals("介紹軟件使用方法",
            mergeSherpaTranscript(listOf("介绍软件", "軟件使用方法"), "zh"))
        assertEquals("介紹軟件使用方法",
            mergeSherpaTranscript(listOf("介紹軟件", "软件使用方法"), "zh"))
    }

    @Test fun automaticLanguageDoesNotConvertHanOutputWithoutChineseDetection() {
        assertEquals("日本の软件使用方法",
            mergeSherpaTranscript(listOf("日本の软件", "软件使用方法"), null))
    }
}
