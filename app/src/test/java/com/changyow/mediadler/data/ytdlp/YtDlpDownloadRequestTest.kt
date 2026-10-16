package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.download.DownloadPlan
import com.changyow.mediadler.core.model.MediaResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YtDlpDownloadRequestTest {
    @Test fun nativeCommandPreservesAllHeadersAndTheirLiteralValues() {
        val headers = linkedMapOf("Referer" to "https://player.example/watch?id=1&v=2",
            "User-Agent" to "media player/1.0", "X-Token" to "a:b+c=1")
        val plan = DownloadPlan(MediaResource("https://cdn.example/master.m3u8", headers),
            null, listOf("-f", "bv*+ba/b"))
        val command = plan.toYoutubeDLRequest().buildCommand()
        assertEquals(headers.map { (name, value) -> "$name:$value" },
            command.indices.filter { command[it] == "--add-headers" }.map { command[it + 1] })
        assertTrue(command.contains(plan.resource.url))
        assertTrue(command.contains("--no-playlist"))
        assertFalse(command.contains("--playlist-items"))
        assertTrue(command.windowed(2).contains(listOf("-f", "bv*+ba/b")))
        assertFalse(command.contains("--limit-rate"))
    }

    @Test fun pageCommandKeepsPlaylistIndexAndVideoAudioMergeSelector() {
        val plan = DownloadPlan(MediaResource("https://example.org/post"), 2, listOf("-f", "137+ba/b"))
        val command = plan.toYoutubeDLRequest().buildCommand()
        assertTrue(command.windowed(2).contains(listOf("--playlist-items", "2")))
        assertTrue(command.windowed(2).contains(listOf("-f", "137+ba/b")))
        assertFalse(command.contains("--no-playlist"))
        assertFalse(command.contains("--add-headers"))
        assertTrue(command.contains(plan.resource.url))
    }

    @Test fun speedCapAddsExactBytesAndKeepsResourceHeadersAndFormat() {
        val plan = DownloadPlan(MediaResource("https://cdn.example/media.m3u8", mapOf("Referer" to "https://example.org/")),
            null, listOf("-f", "best"))
        val command = plan.toYoutubeDLRequest(1_500_000L).buildCommand()
        assertEquals(1, command.count { it == "--limit-rate" })
        assertTrue(command.windowed(2).contains(listOf("--limit-rate", "1500000")))
        assertTrue(command.windowed(2).contains(listOf("--add-headers", "Referer:https://example.org/")))
        assertTrue(command.windowed(2).contains(listOf("-f", "best")))
        assertTrue(command.contains(plan.resource.url))
        assertFalse(plan.toYoutubeDLRequest(null).buildCommand().contains("--limit-rate"))
    }

    @Test fun nonPositiveCapCannotReachNativeDownloader() {
        val plan = DownloadPlan(MediaResource("https://example.org/video.mp4"), null, emptyList())
        listOf(0L, -1L).forEach { rate ->
            assertFailsWith<IllegalArgumentException> { plan.toYoutubeDLRequest(rate) }
        }
    }
}
