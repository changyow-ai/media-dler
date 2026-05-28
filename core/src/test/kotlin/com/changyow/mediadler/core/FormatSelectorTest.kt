package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.FormatSelector
import com.changyow.mediadler.core.model.AudioFormat
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatSelectorTest {
    private fun fmt(id: String, hasAudio: Boolean, isImage: Boolean = false) = MediaFormat(
        formatId = id, ext = "mp4", label = "label", height = 1080,
        hasVideo = !isImage, hasAudio = hasAudio, isImage = isImage, filesizeBytes = null,
    )

    @Test fun bestVideo() {
        assertEquals(listOf("-f", "bv*+ba/b"), FormatSelector.args(FormatSelection.BestVideo))
    }

    @Test fun cappedVideo() {
        assertEquals(
            listOf("-f", "bv*[height<=720]+ba/b[height<=720]/b"),
            FormatSelector.args(FormatSelection.CappedVideo(720)),
        )
    }

    @Test fun audioMp3() {
        assertEquals(
            listOf("-x", "--audio-format", "mp3", "-f", "ba/b"),
            FormatSelector.args(FormatSelection.Audio(AudioFormat.MP3)),
        )
    }

    @Test fun specificVideoOnlyMergesAudio() {
        assertEquals(
            listOf("-f", "137+ba/137/b"),
            FormatSelector.args(FormatSelection.SpecificFormat(fmt("137", hasAudio = false))),
        )
    }

    @Test fun specificProgressiveUsesIdDirectly() {
        assertEquals(
            listOf("-f", "18"),
            FormatSelector.args(FormatSelection.SpecificFormat(fmt("18", hasAudio = true))),
        )
    }

    @Test fun imageNeedsNoFormatArgs() {
        assertEquals(emptyList<String>(), FormatSelector.args(FormatSelection.ImageOriginal))
    }
}
