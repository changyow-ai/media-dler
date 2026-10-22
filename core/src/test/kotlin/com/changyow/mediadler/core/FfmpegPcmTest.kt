package com.changyow.mediadler.core

import com.changyow.mediadler.core.transcribe.FfmpegPcm
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmpegPcmTest {
    @Test fun finiteWindowUsesAccurateSeekAndBoundedOutput() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val args = FfmpegPcm.arguments(File("input with space.m4a"), File("output.pcm"), 57_001, 60_002)
            assertEquals("57.001", args[args.indexOf("-ss") + 1])
            assertEquals("3.001", args[args.indexOf("-t") + 1])
            assertTrue(args.indexOf("-accurate_seek") < args.indexOf("-i"))
            assertEquals("file", args[args.indexOf("-protocol_whitelist") + 1])
            assertEquals("0:a:0", args[args.indexOf("-map") + 1])
            assertEquals("16000", args[args.indexOf("-ar") + 1])
            assertEquals("1", args[args.indexOf("-ac") + 1])
            assertEquals("s16le", args[args.indexOf("-f") + 1])
            assertEquals("pcm_s16le", args[args.indexOf("-c:a") + 1])
            assertTrue(listOf("-xerror", "-nostdin", "-vn", "-sn", "-dn").all { it in args })
            assertTrue(args.indices.filter { args[it] == "-threads" }.all { args[it + 1] == "2" })
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test fun naturalEofIsExplicitAndOtherRangesAreValidated() {
        val input = File("source.m4a")
        val output = File("decoded.pcm")
        val eofArgs = FfmpegPcm.arguments(input, output, 0, Long.MAX_VALUE)
        assertFalse("-t" in eofArgs)
        assertEquals((FfmpegPcm.MAX_EOF_SAMPLES.toLong() * 2 + 1).toString(),
            eofArgs[eofArgs.indexOf("-fs") + 1])
        for ((start, end) in listOf(-1L to 1000L, 1L to 1L, 2L to 1L, Long.MAX_VALUE to Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { FfmpegPcm.arguments(input, output, start, end) }
        }
        assertFailsWith<IllegalArgumentException> { FfmpegPcm.arguments(input, input, 0, 1000) }
    }

    @Test fun signedLittleEndianPcmIsReadWithoutChangingItsLength() = temporaryDirectory { dir ->
        val file = File(dir, "audio.pcm")
        file.writeBytes(byteArrayOf(0, 0, 0, -128, -1, 127, -1, -1))
        assertContentEquals(floatArrayOf(0f, -1f, 32767f / 32768f, -1f / 32768f), FfmpegPcm.read(file, 4))
    }

    @Test fun readerRejectsOversizedOddAndMissingOutput() = temporaryDirectory { dir ->
        val file = File(dir, "audio.pcm")
        assertFailsWith<IllegalArgumentException> { FfmpegPcm.read(file, 10) }
        file.writeBytes(byteArrayOf(0, 0, 0))
        assertFailsWith<IllegalArgumentException> { FfmpegPcm.read(file, 10) }
        file.writeBytes(byteArrayOf(0, 0, 0, 0))
        assertFailsWith<IllegalArgumentException> { FfmpegPcm.read(file, 1) }
        assertFailsWith<IllegalArgumentException> { FfmpegPcm.read(file, -1) }
        file.writeBytes(byteArrayOf())
        assertTrue(FfmpegPcm.read(file, 0).isEmpty())
    }

    @Test fun readerRejectsGiantSparseOutputBeforeAllocating() = temporaryDirectory { dir ->
        val file = File(dir, "giant.pcm")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(Int.MAX_VALUE.toLong() * 4) }
        assertFailsWith<IllegalArgumentException> { FfmpegPcm.read(file, Int.MAX_VALUE) }
    }

    private fun temporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("ffmpeg-pcm-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
