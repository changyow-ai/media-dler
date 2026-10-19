package com.changyow.mediadler.data.ytdlp

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdCleanupFilesTest {
    private fun fixture(test: (File, AdCleanupFiles) -> Unit) {
        val root = Files.createTempDirectory("ad-cleanup-contract-").toFile()
        try {
            val source = File(root, "media.mp4").apply { writeBytes(ByteArray(100) { it.toByte() }) }
            test(source, AdCleanupFiles.create(source))
        } finally { root.deleteRecursively() }
    }

    @Test fun noKnownMatchUsesTheOriginalFile() = fixture { source, files ->
        files.result.writeText("""{"schema":1,"status":"unchanged","removed_ads":0,"removed_seconds":0}""")
        val result = files.readResult()
        assertEquals(source, result.file)
        assertEquals(0, result.removedAds)
        assertFalse(files.output.exists())
        assertEquals(100L, source.length())
    }

    @Test fun validatedCopyIsReturnedWithoutReplacingTheOriginal() = fixture { source, files ->
        files.output.writeBytes(ByteArray(40))
        files.result.writeText("""{"schema":1,"status":"cleaned","removed_ads":2,"removed_seconds":51.5,"output_size":40}""")
        val result = files.readResult()
        assertEquals(files.output, result.file)
        assertEquals(2, result.removedAds)
        assertEquals(51.5, result.removedSeconds)
        assertEquals(100L, source.length())
    }

    @Test fun incompleteOrInconsistentNativeResultsAreRejected() = fixture { _, files ->
        assertFails { files.readResult() }
        files.output.writeBytes(ByteArray(40))
        for (json in listOf(
            "{}",
            """{"schema":2,"status":"cleaned","removed_ads":2,"removed_seconds":51.5,"output_size":40}""",
            """{"schema":1,"status":"cleaned","removed_ads":0,"removed_seconds":51.5,"output_size":40}""",
            """{"schema":1,"status":"cleaned","removed_ads":2,"removed_seconds":-1,"output_size":40}""",
            """{"schema":1,"status":"cleaned","removed_ads":2,"removed_seconds":51.5,"output_size":41}""",
            """{"schema":1,"status":"unchanged","removed_ads":0,"removed_seconds":0}""",
        )) {
            files.result.writeText(json)
            assertFails(json) { files.readResult() }
        }
    }

    @Test fun retryCannotReuseAStaleAcceptedOutput() = fixture { source, first ->
        first.output.writeBytes(ByteArray(40))
        first.result.writeText("""{"schema":1,"status":"cleaned","removed_ads":2,"removed_seconds":51.5,"output_size":40}""")
        assertEquals(2, first.readResult().removedAds)
        val retry = AdCleanupFiles.create(source)
        assertNotEquals(first.directory, retry.directory)
        assertFails { retry.readResult() }
        assertTrue(first.output.isFile)
    }

    @Test fun sourceMutationDuringProcessingIsNotPublished() = fixture { source, files ->
        files.result.writeText("""{"schema":1,"status":"unchanged","removed_ads":0,"removed_seconds":0}""")
        source.appendBytes(byteArrayOf(1))
        assertFails { files.readResult() }
    }

    @Test fun originalCannotBeReturnedAsACleanedHardLink() = fixture { source, files ->
        Files.createLink(files.output.toPath(), source.toPath())
        files.result.writeText("""{"schema":1,"status":"cleaned","removed_ads":2,"removed_seconds":51.5,"output_size":100}""")
        assertFails { files.readResult() }
        assertEquals(100L, source.length())
    }
}
