package com.changyow.mediadler.core.download

import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.MediaKind

object SelectionPlanner {
    /** The default [FormatSelection] for [item] given user [settings] (one-tap mode). */
    fun defaultSelection(item: MediaItem, settings: AppSettings): FormatSelection {
        if (item.isImage) return FormatSelection.ImageOriginal
        if (settings.defaultMediaKind == MediaKind.AUDIO) {
            return FormatSelection.Audio(settings.audioFormat)
        }
        val cap = settings.defaultVideoQuality.maxHeight
        return if (cap == null) FormatSelection.BestVideo else FormatSelection.CappedVideo(cap)
    }
}
