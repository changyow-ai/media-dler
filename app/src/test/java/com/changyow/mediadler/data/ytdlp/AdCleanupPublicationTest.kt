package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.model.StorageMode
import com.changyow.mediadler.core.repo.DownloadOutput
import com.changyow.mediadler.data.storage.CompletedArtifact
import com.changyow.mediadler.data.storage.CompletedArtifactPublication
import com.changyow.mediadler.data.storage.PublicationTarget
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AdCleanupPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val taskId = "task-123"

    private fun source() = File(temporary.root, "$taskId/hash/media.mp4").apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(100) { it.toByte() })
    }

    private fun cleaned(source: File): Pair<AdCleanupFiles, AdCleanupResult> {
        val files = AdCleanupFiles.create(source)
        files.output.writeBytes(ByteArray(40) { (it + 1).toByte() })
        files.result.writeText("""{"schema":1,"status":"cleaned","removed_ads":2,"removed_seconds":51.5,"output_size":40}""")
        return files to files.readResult()
    }

    private fun artifact(file: File) = CompletedArtifact(file.canonicalPath, file.length(),
        StorageMode.SAF, "tree", emptyList(), "cleaned.mp4", "video/mp4", "token")

    @Test fun repeatedFailuresBeforeJournalCreationDoNotAccumulateCleanedCopies() = runTest {
        val source = source()
        val original = source.readBytes()
        val journal = CompletedArtifactPublication(temporary.root)
        repeat(3) {
            val (files, result) = cleaned(source)
            val failure = SecurityException("SAF permission revoked")
            assertSame(failure, assertFailsWith<SecurityException> {
                result.useForPublication(source, { journal.hasRecord(taskId) }) { throw failure }
            })
            assertFalse(files.directory.exists(), "Unrecorded cleanup attempt must be removed")
            assertFalse(journal.hasRecord(taskId))
            assertContentEquals(original, source.readBytes())
        }
        assertTrue(source.parentFile!!.listFiles().orEmpty().none { it.name.startsWith(".ad-cleanup-") })
    }

    @Test fun initialJournalWriteFailureDiscardsOnlyTheCleanedAttempt() = runTest {
        val source = source()
        val original = source.readBytes()
        val (files, result) = cleaned(source)
        val journal = CompletedArtifactPublication(temporary.root)
        // A directory at the temporary record path deterministically prevents the initial write.
        assertTrue(File(temporary.root, "$taskId/publication.json.new").mkdir())
        assertFailsWith<IOException> {
            result.useForPublication(source, { journal.hasRecord(taskId) }) { file ->
                journal.write(taskId, artifact(file))
            }
        }
        assertFalse(journal.hasRecord(taskId))
        assertFalse(files.directory.exists())
        assertContentEquals(original, source.readBytes())
    }

    @Test fun cancellationBeforeJournalCreationDiscardsTheCleanedAttempt() = runTest {
        val source = source()
        val (files, result) = cleaned(source)
        val journal = CompletedArtifactPublication(temporary.root)
        val publishing = CompletableDeferred<Unit>()
        val job = launch {
            result.useForPublication(source, { journal.hasRecord(taskId) }) {
                publishing.complete(Unit)
                awaitCancellation()
            }
        }
        publishing.await()
        job.cancelAndJoin()
        assertFalse(files.directory.exists())
        assertEquals(100L, source.length())
    }

    @Test fun failureAfterJournalCreationKeepsCleanedArtifactForRecovery() = runTest {
        val source = source()
        val (files, result) = cleaned(source)
        val expected = result.file.readBytes()
        val journal = CompletedArtifactPublication(temporary.root)
        var failCopy = true
        var copied = byteArrayOf()
        val target = object : PublicationTarget {
            override suspend fun findOrCreate(artifact: CompletedArtifact) = "staging-uri"
            override suspend fun copy(artifact: CompletedArtifact, uri: String, source: File) {
                if (failCopy) throw IOException("copy failed")
                copied = source.readBytes()
            }
            override suspend fun finish(artifact: CompletedArtifact, uri: String) =
                DownloadOutput(uri, artifact.displayName, artifact.mimeType)
            override fun discard(artifact: CompletedArtifact) = true
        }
        assertFailsWith<IOException> {
            result.useForPublication(source, { journal.hasRecord(taskId) }) { file ->
                journal.write(taskId, artifact(file))
                journal.recover(taskId) { target }
            }
        }
        assertTrue(files.output.isFile)
        assertEquals(files.output.canonicalPath, journal.read(taskId)?.sourcePath)
        failCopy = false
        assertEquals("staging-uri", journal.recover(taskId) { target }?.uri)
        assertContentEquals(expected, copied)
        assertEquals(100L, source.length())
    }

    @Test fun originalIsPreservedWhenThereWasNoAdMatch() = runTest {
        val source = source()
        val original = source.readBytes()
        val journal = CompletedArtifactPublication(temporary.root)
        val result = AdCleanupResult(source, 0, 0.0)
        assertFailsWith<IOException> {
            result.useForPublication(source, { journal.hasRecord(taskId) }) {
                throw IOException("destination unavailable")
            }
        }
        assertContentEquals(original, source.readBytes())
    }

    @Test fun anExistingCorruptRecordConservativelyRetainsTheRecoveryArtifact() = runTest {
        val source = source()
        val (files, result) = cleaned(source)
        val journal = CompletedArtifactPublication(temporary.root)
        File(temporary.root, "$taskId/publication.json").writeText("truncated{")
        assertFailsWith<IllegalArgumentException> {
            result.useForPublication(source, { journal.hasRecord(taskId) }) {
                journal.read(taskId)
            }
        }
        assertTrue(files.output.isFile)
        assertEquals(100L, source.length())
    }
}
