package com.changyow.mediadler.download

import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.EpisodePage
import com.changyow.mediadler.core.model.EpisodeMetadata
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.QueuedSpec
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import com.changyow.mediadler.core.repo.MediaExtractor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertNull

class QueuedRequestResolverTest {
    private val page = EpisodePage("https://8maple.st/play/test-2-4.html", SourceKind.EIGHT_MAPLE,
        catalogUrl = "https://8maple.st/video/test.html", lineId = "2", seriesTitle = "測試影集", episodeNumber = 4)
    private fun item(url: String, kind: SourceKind = SourceKind.EIGHT_MAPLE) = MediaItem(
        url, null, "episode4", "第04集", null, null, false, emptyList(),
        SourceContext(page.playbackUrl, page.playbackUrl, kind), DownloadTarget.DIRECT,
    )

    @Test fun retriesResolveThePageAgainRatherThanUsingAnExpiredStream() = runTest {
        val calls = mutableListOf<String>()
        val resolver = QueuedRequestResolver(object : MediaExtractor {
            override suspend fun extract(url: String): Result<List<MediaItem>> {
                calls += url
                return Result.success(listOf(item("https://cdn.example/video.m3u8?token=${calls.size}")))
            }
        })
        val spec = QueuedSpec.Page(page, FormatSelection.CappedVideo(720))
        val first = resolver.resolve(spec)
        val second = resolver.resolve(spec)
        assertEquals(listOf(page.playbackUrl, page.playbackUrl), calls)
        assertEquals("https://cdn.example/video.m3u8?token=1", first.item.sourceUrl)
        assertEquals("https://cdn.example/video.m3u8?token=2", second.item.sourceUrl)
        assertEquals("測試影集", second.item.seriesTitle)
        assertEquals(4, second.item.episodeNumber)
        assertEquals(FormatSelection.CappedVideo(720), second.selection)
    }

    @Test fun generalImageAndManualFormatRequestsRetainTheirExistingResolvedPath() = runTest {
        val original = DownloadRequest(item("https://cdn.example/photo.jpg", SourceKind.GENERAL).copy(isImage = true), FormatSelection.ImageOriginal)
        val resolver = QueuedRequestResolver(object : MediaExtractor {
            override suspend fun extract(url: String): Result<List<MediaItem>> = error("一般來源不應再次解析")
        })
        assertSame(original, resolver.resolve(QueuedSpec.Resolved(original)))
    }

    @Test fun multiItemImagesAndWrongSourceCannotSilentlyDownloadAnotherItem() = runTest {
        for (items in listOf(emptyList(), listOf(item("a"), item("b")), listOf(item("a").copy(isImage = true)), listOf(item("a", SourceKind.GENERAL)))) {
            val resolver = QueuedRequestResolver(object : MediaExtractor {
                override suspend fun extract(url: String) = Result.success(items)
            })
            assertFailsWith<IllegalStateException> { resolver.resolve(QueuedSpec.Page(page, FormatSelection.BestVideo)) }
        }
    }

    @Test fun parsingFailureRemainsAFailureForTheDurableTask() = runTest {
        val resolver = QueuedRequestResolver(object : MediaExtractor {
            override suspend fun extract(url: String) = Result.failure<List<MediaItem>>(IllegalStateException("播放頁暫時無法連線"))
        })
        assertEquals("播放頁暫時無法連線", assertFailsWith<IllegalStateException> {
            resolver.resolve(QueuedSpec.Page(page, FormatSelection.BestVideo))
        }.message)
    }

    @Test fun explicitMetadataCorrectionSurvivesFreshExtractionIncludingClearedUnknownFields() = runTest {
        val override = EpisodeMetadata("人工修正標題", null, null, 7)
        val corrected = page.copy(metadataOverride = override)
        val resolver = QueuedRequestResolver(object : MediaExtractor {
            override suspend fun extract(url: String) = Result.success(listOf(item("https://cdn.example/fresh.m3u8")
                .copy(title = "新解析標題", seriesTitle = "站方影集", seasonNumber = 9, episodeNumber = 99)))
        })
        val first = resolver.resolve(QueuedSpec.Page(corrected, FormatSelection.BestVideo))
        val second = resolver.resolve(QueuedSpec.Page(corrected, FormatSelection.BestVideo))
        for (request in listOf(first, second)) {
            assertEquals("人工修正標題", request.item.title)
            assertNull(request.item.seriesTitle)
            assertNull(request.item.seasonNumber)
            assertEquals(7, request.item.episodeNumber)
            assertEquals(page.originalUrl, request.item.source.originalUrl)
        }
    }
}
