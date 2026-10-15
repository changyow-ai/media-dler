package com.changyow.mediadler.data

import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem
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

        assertEquals(listOf(hosted), items)
        assertEquals(emptyList(), fallback.urls)
    }

    @Test fun shopeeProductAndShareUseDedicatedExtractor() = runTest {
        val result = Result.success(listOf(media("https://cdn.example/video.mp4", "ShopeeVideo", null, 1280)))
        val shopee = RecordingExtractor(result)
        val fallback = unusedExtractor()
        val threads = unusedExtractor()
        val extractor = RoutingMediaExtractor(threads, fallback, shopee)
        val urls = listOf("https://tw.shp.ee/pcTWnyJ1", "https://shopee.tw/product/50662979/28020313896")

        urls.forEach { assertEquals(result.getOrThrow(), extractor.extract(it).getOrThrow()) }

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
