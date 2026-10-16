package com.changyow.mediadler.core.extract

import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.SourceKind

/** Reads 8maple's static MacCMS player data. */
object EightMapleMediaParser {
    const val USER_AGENT = MacCmsMediaParser.USER_AGENT

    fun parse(html: String, pageUrl: String, originalUrl: String = pageUrl): MediaItem =
        MacCmsMediaParser.parse(html, pageUrl, originalUrl, SourceKind.EIGHT_MAPLE)
}
