package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.BoundedAudioWindows
import com.changyow.mediadler.core.transcribe.FfmpegPcm
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Actual FFmpeg regressions; skipped explicitly when FFmpeg is not installed on the test host. */
class FfmpegPcmIntegrationTest {
    @Test fun aacPrimingEditListDecodesWholeShortClipAndExactFiniteRange() = withFfmpeg { dir ->
        val input = File(dir, "short 'Chinese-style' stereo 22050.m4a")
        val output = File(dir, "decoded.pcm")
        run(dir, listOf("-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
            "sine=frequency=997:sample_rate=22050:duration=4.375", "-ac", "2", "-c:a", "aac", input.path))
        // FFmpeg AAC-in-MP4 stores encoder priming in an edit list, as in the reported short clips.
        assertTrue(input.readBytes().toString(Charsets.ISO_8859_1).contains("elst"))
        run(dir, FfmpegPcm.arguments(input, output, 0, Long.MAX_VALUE))
        val whole = FfmpegPcm.read(output, 5 * FfmpegPcm.SAMPLE_RATE)
        assertTrue(whole.size in 69_000..72_000, "Unexpected AAC length: ${whole.size}")
        assertTrue(whole.any { abs(it) > 0.01f }, "Decoded PCM must contain the test signal")

        run(dir, FfmpegPcm.arguments(input, output, 1234, 2345))
        assertEquals(17_776, FfmpegPcm.read(output, 17_776).size)
        run(dir, FfmpegPcm.arguments(input, output, 1234, 1235))
        assertEquals(16, FfmpegPcm.read(output, 16).size)
        run(dir, FfmpegPcm.arguments(input, output, 6000, 7000))
        assertTrue(FfmpegPcm.read(output, 16_000).isEmpty(), "A genuine EOF remains distinguishable")
    }

    @Test fun malformedInputAndNestedNetworkUrlsAreRejected() = withFfmpeg { dir ->
        val input = File(dir, "bad.m4a").apply { writeText("not an audio container") }
        val output = File(dir, "decoded.pcm")
        assertTrue(run(dir, FfmpegPcm.arguments(input, output, 0, 1000), expectSuccess = false).first != 0)
        val playlist = File(dir, "network.m3u8").apply {
            writeText("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nhttp://127.0.0.1:9/segment.aac\n#EXT-X-ENDLIST\n")
        }
        val (exit, diagnostic) = run(dir, FfmpegPcm.arguments(playlist, output, 0, 1000), expectSuccess = false)
        assertTrue(exit != 0)
        assertTrue("not on whitelist" in diagnostic, diagnostic)
    }

    @Test fun naturalEofCapCannotBeMistakenForSuccessfulCompletePcm() = withFfmpeg { dir ->
        val input = File(dir, "long.wav")
        val output = File(dir, "capped.pcm")
        run(dir, listOf("-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
            "anullsrc=r=8000:cl=mono", "-t", "601", "-c:a", "pcm_s16le", input.path))
        run(dir, FfmpegPcm.arguments(input, output, 0, Long.MAX_VALUE))
        val limitBytes = FfmpegPcm.MAX_EOF_SAMPLES.toLong() * 2
        assertTrue(output.length() in (limitBytes + 1)..(limitBytes + 65_536),
            "FFmpeg must stop near its byte limit: ${output.length()}")
        assertFailsWith<IllegalArgumentException> { FfmpegPcm.read(output, FfmpegPcm.MAX_EOF_SAMPLES) }
    }

    @Test fun understatedDurationContinuesButExactBoundaryDoesNotRepeatOverlap() = withFfmpeg { dir ->
        for (actualSeconds in listOf(60, 90)) {
            val input = File(dir, "actual-$actualSeconds.wav")
            val output = File(dir, "window.pcm")
            run(dir, listOf("-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
                "sine=frequency=997:sample_rate=16000:duration=$actualSeconds", "-c:a", "pcm_s16le", input.path))
            val windows = BoundedAudioWindows(10_000, 60_000, 3_000, 16_000)
            val acceptedCounts = mutableListOf<Int>()
            var foundEnd = false
            for (index in 0..3) {
                val window = windows.windowAt(index)
                run(dir, FfmpegPcm.arguments(input, output, window.startMs, window.endMs))
                val pcm = FfmpegPcm.read(output, 960_000)
                val decision = windows.afterDecode(index, pcm.size)
                if (decision.action == BoundedAudioWindows.Action.END_OF_INPUT) {
                    // These probes never reach inference or advance the checkpoint.
                    assertEquals(if (actualSeconds == 60) 48_000 else 0, pcm.size)
                    foundEnd = true
                    break
                }
                assertEquals(BoundedAudioWindows.Action.ACCEPT_MORE, decision.action)
                assertEquals(0, decision.totalWindows)
                acceptedCounts.add(pcm.size)
            }
            assertTrue(foundEnd)
            assertEquals(if (actualSeconds == 60) listOf(960_000) else listOf(960_000, 528_000), acceptedCounts)
        }
    }

    private fun withFfmpeg(block: (File) -> Unit) {
        val available = runCatching {
            val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            process.inputStream.use { it.readBytes() }
            process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
        assumeTrue("Host FFmpeg is required for the real decoder regressions", available)
        val directory = Files.createTempDirectory("ffmpeg-pcm-integration").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun run(dir: File, args: List<String>, expectSuccess: Boolean = true): Pair<Int, String> {
        val log = File.createTempFile("ffmpeg-", ".log", dir)
        val process = ProcessBuilder(listOf("ffmpeg") + args).redirectErrorStream(true).redirectOutput(log).start()
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor()
        }
        assertTrue(completed, "FFmpeg timed out")
        val diagnostic = log.readText()
        val exit = process.exitValue()
        if (expectSuccess) assertEquals(0, exit, diagnostic)
        return exit to diagnostic
    }
}
