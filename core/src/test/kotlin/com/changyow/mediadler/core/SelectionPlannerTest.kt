package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.SelectionPlanner
import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.AudioFormat
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.MediaKind
import com.changyow.mediadler.core.model.VideoQuality
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionPlannerTest {
    private val video = MediaItem(
        sourceUrl = "u", playlistIndex = null, id = "id", title = "t",
        thumbnailUrl = null, durationSeconds = null, isImage = false, formats = emptyList(),
    )
    private val image = video.copy(isImage = true)

    @Test fun imageAlwaysOriginalEvenWhenAudioDefault() {
        assertEquals(
            FormatSelection.ImageOriginal,
            SelectionPlanner.defaultSelection(image, AppSettings(defaultMediaKind = MediaKind.AUDIO)),
        )
    }

    @Test fun audioWhenKindAudio() {
        assertEquals(
            FormatSelection.Audio(AudioFormat.M4A),
            SelectionPlanner.defaultSelection(
                video,
                AppSettings(defaultMediaKind = MediaKind.AUDIO, audioFormat = AudioFormat.M4A),
            ),
        )
    }

    @Test fun bestWhenQualityBest() {
        assertEquals(
            FormatSelection.BestVideo,
            SelectionPlanner.defaultSelection(video, AppSettings(defaultVideoQuality = VideoQuality.BEST)),
        )
    }

    @Test fun cappedWhenQualitySet() {
        assertEquals(
            FormatSelection.CappedVideo(720),
            SelectionPlanner.defaultSelection(video, AppSettings(defaultVideoQuality = VideoQuality.P720)),
        )
    }
}
