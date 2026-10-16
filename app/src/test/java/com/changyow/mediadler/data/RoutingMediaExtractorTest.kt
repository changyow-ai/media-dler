package com.changyow.mediadler.data

import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import com.changyow.mediadler.core.repo.MediaExtractor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RoutingMediaExtractorTest {
    @Test fun linkedInstagramReelIsResolvedThroughFallback() = runTest {
        val placeholder = media(
            sourceUrl = "https://www.instagram.com/reel/DbV0B5SNN5V/",
            title = "ThreadsVideo_DbV4RKnGKKV",
            thumbnailUrl = "https://cdn.example/reel-preview.jpg",
            height = null,
        )
        val resolved = media(
            sourceUrl = placeholder.sourceUrl,
            title = "Video by figure_versee",
            thumbnailUrl = "https://cdn.example/instagram-thumbnail.jpg",
            height = 1280,
        )
        val threads = RecordingExtractor(Result.success(listOf(placeholder)))
        val fallback = RecordingExtractor(Result.success(listOf(resolved)))

        val items = RoutingMediaExtractor(threads, fallback, unusedExtractor())
            .extract("https://www.threads.com/share/BAVuGHGtpT/")
            .getOrThrow()

        assertEquals(listOf(placeholder.sourceUrl), fallback.urls)
        assertEquals(1, items.size)
        assertFalse(items.single().isImage)
        assertEquals("ThreadsVideo_DbV4RKnGKKV", items.single().title)
        assertEquals(1280, items.single().formats.single().height)
        assertEquals("https://cdn.example/instagram-thumbnail.jpg", items.single().thumbnailUrl)
        assertEquals("https://www.threads.com/share/BAVuGHGtpT/", items.single().source.originalUrl)
        assertEquals(placeholder.sourceUrl, items.single().source.pageUrl)
    }

    @Test fun hostedThreadsVideoDoesNotUseFallback() = runTest {
        val hosted = media(
            sourceUrl = "https://instagram.example.fbcdn.net/o1/v/THREADS.mp4?x=1",
            title = "ThreadsVideo_ABC",
            thumbnailUrl = null,
            height = null,
        )
        val threads = RecordingExtractor(Result.success(listOf(hosted)))
        val fallback = RecordingExtractor(Result.failure(AssertionError("must not be called")))

        val items = RoutingMediaExtractor(threads, fallback, unusedExtractor())
            .extract("https://www.threads.com/@u/post/ABC")
            .getOrThrow()

        assertEquals(listOf(hosted.copy(source = hosted.source.copy(originalUrl = "https://www.threads.com/@u/post/ABC"))), items)
        assertEquals(emptyList(), fallback.urls)
    }

    @Test fun shopeeProductAndShareUseDedicatedExtractor() = runTest {
        val result = Result.success(listOf(media("https://cdn.example/video.mp4", "ShopeeVideo", null, 1280)))
        val shopee = RecordingExtractor(result)
        val fallback = unusedExtractor()
        val threads = unusedExtractor()
        val extractor = RoutingMediaExtractor(threads, fallback, shopee)
        val urls = listOf("https://tw.shp.ee/pcTWnyJ1", "https://shopee.tw/product/50662979/28020313896")

        urls.forEach { url ->
            assertEquals(result.getOrThrow().map { it.copy(source = it.source.copy(originalUrl = url)) },
                extractor.extract(url).getOrThrow())
        }

        assertEquals(urls, shopee.urls)
        assertEquals(emptyList(), fallback.urls)
        assertEquals(emptyList(), threads.urls)
    }

    @Test fun failedShopeeExtractionDoesNotFallBackToUnscopedYtDlp() = runTest {
        val failure = IllegalStateException("No public product data")
        val fallback = unusedExtractor()
        val extractor = RoutingMediaExtractor(unusedExtractor(), fallback,
            RecordingExtractor(Result.failure(failure)))

        assertEquals(failure, extractor.extract("https://tw.shp.ee/pcTWnyJ1").exceptionOrNull())
        assertEquals(emptyList(), fallback.urls)
    }

    @Test fun directInstagramVideoStillUsesYtDlp() = runTest {
        val url = "https://www.instagram.com/reel/DbV0B5SNN5V/"
        val result = Result.success(listOf(media(url, "InstagramVideo", null, 1280)))
        val fallback = RecordingExtractor(result)
        val shopee = unusedExtractor()

        assertEquals(result.getOrThrow(), RoutingMediaExtractor(unusedExtractor(), fallback, shopee)
            .extract(url).getOrThrow())
        assertEquals(listOf(url), fallback.urls)
        assertEquals(emptyList(), shopee.urls)
    }

    @Test fun eightMapleUsesDedicatedParserAndPreservesItsCdnIndependentIdentity() = runTest {
        val page = "https://play.8maple.st/play/OD4C-2-4.html"
        val video = media("https://unrelated.example/master.m3u8", "影集 第04集", null, null)
            .copy(source = SourceContext(page, page, SourceKind.EIGHT_MAPLE), episodeNumber = 4)
        val special = RecordingExtractor(Result.success(listOf(video)))
        val fallback = unusedExtractor()
        val result = RoutingMediaExtractor(unusedExtractor(), fallback, unusedExtractor(), special)
            .extract(page).getOrThrow().single()
        assertEquals(video, result)
        assertEquals(listOf(page), special.urls)
        assertEquals(emptyList(), fallback.urls)
        assertEquals(SourceKind.EIGHT_MAPLE, result.source.kind)
    }

    @Test fun failedEightMapleDoesNotFallBackToAnUnscopedExtractor() = runTest {
        val failure = IllegalStateException("8maple 播放資料缺失")
        val fallback = unusedExtractor()
        val result = RoutingMediaExtractor(unusedExtractor(), fallback, unusedExtractor(),
            RecordingExtractor(Result.failure(failure))).extract("https://play.8maple.st/play/OD4C-2-4.html")
        assertEquals(failure, result.exceptionOrNull())
        assertEquals(emptyList(), fallback.urls)
    }

    @Test fun unrelatedSiteWithEightMapleInItsPathStillUsesGeneralExtractor() = runTest {
        val url = "https://cdn.example/8maple/video.mp4"
        val general = media(url, "General", null, null)
        val special = unusedExtractor()
        val result = RoutingMediaExtractor(unusedExtractor(), RecordingExtractor(Result.success(listOf(general))),
            unusedExtractor(), special).extract(url).getOrThrow().single()
        assertEquals(general, result)
        assertEquals(SourceKind.GENERAL, result.source.kind)
        assertEquals(emptyList(), special.urls)
    }

    @Test fun gimyAliasUsesDedicatedParserAndKeepsOriginalShareUrl() = runTest {
        val original = "https://gimytw.cc/eps/202561679-2.html"
        val canonical = "https://gimytv.io/eps/202561679-2.html"
        val video = media("https://cdn.example/index.m3u8", "第02集", null, null)
            .copy(source = SourceContext(canonical, canonical, SourceKind.GIMY))
        val special = RecordingExtractor(Result.success(listOf(video)))
        val fallback = unusedExtractor()
        val result = RoutingMediaExtractor(unusedExtractor(), fallback, unusedExtractor(), gimy = special)
            .extract(original).getOrThrow().single()
        assertEquals(original, result.source.originalUrl)
        assertEquals(canonical, result.source.pageUrl)
        assertEquals(SourceKind.GIMY, result.source.kind)
        assertEquals(listOf(original), special.urls)
        assertEquals(emptyList(), fallback.urls)
    }

    @Test fun failedGimyDoesNotFallBackToGeneralExtraction() = runTest {
        val failure = IllegalStateException("Gimy 播放器資料缺失")
        val fallback = unusedExtractor()
        val result = RoutingMediaExtractor(unusedExtractor(), fallback, unusedExtractor(),
            gimy = RecordingExtractor(Result.failure(failure))).extract("https://gimytv.io/eps/202561679-2.html")
        assertEquals(failure, result.exceptionOrNull())
        assertEquals(emptyList(), fallback.urls)
    }

    @Test fun gimyAiPlaybackUsesTheDedicatedExtractor() = runTest {
        val page = "https://gimyai.tw/play/916639-3-1.html"
        val video = media("https://cdn.example/index.m3u8", "第 1 集", null, null)
            .copy(source = SourceContext(page, page, SourceKind.GIMY))
        val special = RecordingExtractor(Result.success(listOf(video)))
        val fallback = unusedExtractor()
        val result = RoutingMediaExtractor(unusedExtractor(), fallback, unusedExtractor(), gimy = special)
            .extract(page).getOrThrow().single()
        assertEquals(video, result)
        assertEquals(listOf(page), special.urls)
        assertEquals(emptyList(), fallback.urls)
    }

    private fun unusedExtractor() = RecordingExtractor(Result.failure(AssertionError("must not be called")))

    private fun media(
        sourceUrl: String,
        title: String,
        thumbnailUrl: String?,
        height: Int?,
    ) = MediaItem(
        sourceUrl = sourceUrl,
        playlistIndex = null,
        id = title,
        title = title,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = null,
        isImage = false,
        formats = listOf(
            MediaFormat(
                formatId = "best",
                ext = "mp4",
                label = "影片",
                height = height,
                hasVideo = true,
                hasAudio = true,
                isImage = false,
                filesizeBytes = null,
            ),
        ),
    )

    private class RecordingExtractor(
        private val result: Result<List<MediaItem>>,
    ) : MediaExtractor {
        val urls = mutableListOf<String>()

        override suspend fun extract(url: String): Result<List<MediaItem>> {
            urls += url
            return result
        }
    }
}
