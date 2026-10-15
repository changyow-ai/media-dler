package com.changyow.mediadler.core

import com.changyow.mediadler.core.download.DownloadPlanner
import com.changyow.mediadler.core.download.FormatSelector
import com.changyow.mediadler.core.model.AudioFormat
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.MediaResource
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DownloadPlannerTest {
    private val page = "https://example.org/watch/123"
    private val stream = "https://cdn.example/gimy/video.m3u8?expires=123"
    private val format = MediaFormat("site-720", "mp4", "720p", 720, true, true, false, null,
        MediaResource(stream, mapOf("Referer" to page)))
    private val item = MediaItem(page, 2, "123", "原始標題 第5集", null, null, false, listOf(format))

    @Test fun legacyConstructorHasGeneralPageDefaultsAndUnknownEpisodeMetadata() {
        assertEquals(SourceContext(page, page), item.source)
        assertEquals(SourceKind.GENERAL, item.source.kind)
        assertEquals(DownloadTarget.PAGE, item.downloadTarget)
        assertEquals("原始標題 第5集", item.originalTitle)
        assertNull(item.seriesTitle)
        assertNull(item.seasonNumber)
        assertNull(item.episodeNumber)
    }

    @Test fun pageDownloadsReextractAndPreserveEveryExistingFormatSelection() {
        val selections = listOf(FormatSelection.BestVideo, FormatSelection.CappedVideo(720),
            FormatSelection.SpecificFormat(format), FormatSelection.Audio(AudioFormat.M4A),
            FormatSelection.Audio(AudioFormat.MP3), FormatSelection.ImageOriginal)
        for (selection in selections) {
            val plan = DownloadPlanner.plan(DownloadRequest(item, selection))
            assertEquals(page, plan.resource.url)
            assertEquals(2, plan.playlistIndex)
            assertEquals(FormatSelector.args(selection), plan.formatArguments)
            // An observed stream's Referer must not be applied to the page request.
            assertEquals(emptyMap(), plan.resource.httpHeaders)
        }
    }

    @Test fun specialSourceIdentityAndPagesSurviveDirectCdnSelection() {
        for (kind in listOf(SourceKind.GIMY, SourceKind.EIGHT_MAPLE)) {
            val original = "https://share.example/123"
            val special = item.copy(sourceUrl = stream, source = SourceContext(original, page, kind),
                downloadTarget = DownloadTarget.DIRECT, httpHeaders = mapOf("User-Agent" to "player"),
                seriesTitle = "劇名", episodeNumber = 5)
            val request = DownloadRequest(special, FormatSelection.SpecificFormat(format))
            val plan = DownloadPlanner.plan(request)
            assertEquals(stream, plan.resource.url)
            assertEquals(mapOf("User-Agent" to "player", "Referer" to page), plan.resource.httpHeaders)
            assertNull(plan.playlistIndex)
            assertFalse(plan.formatArguments.any { it.contains("site-720") })
            assertEquals(SourceContext(original, page, kind), request.item.source)
            assertEquals("原始標題 第5集", request.item.originalTitle)
            assertEquals(5, request.item.episodeNumber)
            assertNull(request.item.seasonNumber)
        }
    }

    @Test fun directSpecificFormatSelectsItsOwnResourceAndOverridesHeadersIgnoringCase() {
        val selected = format.copy(resource = MediaResource("https://cdn.example/selected.m3u8",
            mapOf("referer" to "https://player.example/iframe", "X-Token" to "resource-token")))
        val direct = item.copy(sourceUrl = stream, downloadTarget = DownloadTarget.DIRECT,
            httpHeaders = mapOf("Referer" to page, "User-Agent" to "player"))
        val plan = DownloadPlanner.plan(DownloadRequest(direct, FormatSelection.SpecificFormat(selected)))
        assertEquals("https://cdn.example/selected.m3u8", plan.resource.url)
        assertEquals(mapOf("referer" to "https://player.example/iframe", "User-Agent" to "player",
            "X-Token" to "resource-token"), plan.resource.httpHeaders)
        assertEquals(FormatSelector.args(FormatSelection.BestVideo), plan.formatArguments)
    }

    @Test fun directFallbackRetainsTargetWithoutInventingPlaylistOrFormatId() {
        val direct = item.copy(sourceUrl = stream, downloadTarget = DownloadTarget.DIRECT,
            formats = listOf(format.copy(resource = null)), httpHeaders = mapOf("Referer" to page))
        val plan = DownloadPlanner.plan(DownloadRequest(direct, FormatSelection.SpecificFormat(direct.formats.single())))
        assertEquals(MediaResource(stream, mapOf("Referer" to page)), plan.resource)
        assertNull(plan.playlistIndex)
        assertFalse(plan.formatArguments.any { it.contains("site-720") })
    }

    @Test fun directAudioAndImageKeepTheirOutputKinds() {
        val direct = item.copy(sourceUrl = stream, downloadTarget = DownloadTarget.DIRECT)
        val audio = DownloadPlanner.plan(DownloadRequest(direct, FormatSelection.Audio(AudioFormat.M4A)))
        assertEquals(MediaResource(stream, mapOf("Referer" to page)), audio.resource)
        assertEquals(FormatSelector.args(FormatSelection.Audio(AudioFormat.M4A)), audio.formatArguments)
        val imageFormat = format.copy(isImage = true, hasAudio = false, hasVideo = false,
            resource = MediaResource("https://cdn.example/photo.jpg"))
        val image = DownloadPlanner.plan(DownloadRequest(direct.copy(isImage = true),
            FormatSelection.SpecificFormat(imageFormat)))
        assertEquals("https://cdn.example/photo.jpg", image.resource.url)
        assertEquals(emptyList(), image.formatArguments)
    }

    @Test fun cdnNameCannotTurnAGeneralSourceIntoASpecialSource() {
        val direct = item.copy(sourceUrl = stream, downloadTarget = DownloadTarget.DIRECT)
        val request = DownloadRequest(direct, FormatSelection.BestVideo)
        assertEquals(stream, DownloadPlanner.plan(request).resource.url)
        assertEquals(SourceKind.GENERAL, request.item.source.kind)
        assertEquals(page, request.item.source.originalUrl)
    }
}
