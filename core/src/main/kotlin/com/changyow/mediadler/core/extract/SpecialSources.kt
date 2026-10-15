package com.changyow.mediadler.core.extract

import com.changyow.mediadler.core.model.EpisodePage
import com.changyow.mediadler.core.model.SourceKind

/** Explicit product scope; arbitrary media CDNs never become special sources. */
object SpecialSources {
    fun episodePageOrNull(url: String): EpisodePage? = when {
        EightMapleUrl.playbackOrNull(url) != null -> EpisodePage(url, SourceKind.EIGHT_MAPLE)
        GimyUrl.playbackId(url) != null -> EpisodePage(url, SourceKind.GIMY)
        else -> null
    }
}
