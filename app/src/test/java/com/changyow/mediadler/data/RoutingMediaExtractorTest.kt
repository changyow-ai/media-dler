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

        val items = RoutingMediaExtractor(threads, fallback)
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

        val items = RoutingMediaExtractor(threads, fallback)
            .extract("https://www.threads.com/@u/post/ABC")
            .getOrThrow()

        assertEquals(listOf(hosted), items)
        assertEquals(emptyList(), fallback.urls)
    }

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
