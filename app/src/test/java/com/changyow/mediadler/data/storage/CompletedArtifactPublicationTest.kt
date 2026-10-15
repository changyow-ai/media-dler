package com.changyow.mediadler.data.storage

import com.changyow.mediadler.core.model.StorageMode
import com.changyow.mediadler.core.repo.DownloadOutput
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletedArtifactPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val taskId = "task-123"
    private fun source(root: File) = File(root, "$taskId/hash/media.mp4").apply {
        parentFile.mkdirs(); writeBytes(ByteArray(91) { it.toByte() })
    }
    private fun artifact(source: File) = CompletedArtifact(source.canonicalPath, source.length(),
        StorageMode.SAF, "original-tree", listOf("drama", "劇名"), "E0001 影片 [token].mp4",
        "video/mp4", "token")

    private class FakeTarget : PublicationTarget {
        var creates = 0
        var copies = 0
        var finishes = 0
        var bytes = byteArrayOf()
        var row: String? = null
        var visible = false
        var failAfterCreate = false
        var failCopy = false
        var failAfterVisible = false
        var lastArtifact: CompletedArtifact? = null
        var discards = 0
        var failDiscard = false

        override fun discard(artifact: CompletedArtifact): Boolean {
            discards++
            if (failDiscard) return false
            if (!visible) row = null
            return true
        }

        override suspend fun findOrCreate(artifact: CompletedArtifact): String {
            lastArtifact = artifact
            if (row == null) { creates++; row = "own-staging-uri" }
            if (failAfterCreate) { failAfterCreate = false; error("crash after external create") }
            return requireNotNull(row)
        }
        override suspend fun copy(artifact: CompletedArtifact, uri: String, source: File) {
            copies++
            bytes = source.readBytes()
            if (failCopy) { bytes = bytes.take(3).toByteArray(); failCopy = false; error("copy failed") }
        }
        override suspend fun finish(artifact: CompletedArtifact, uri: String): DownloadOutput {
            finishes++
            check(bytes.size.toLong() == artifact.size)
            visible = true
            if (failAfterVisible) { failAfterVisible = false; error("crash after rename/visibility") }
            return DownloadOutput("own-final-uri", artifact.displayName, artifact.mimeType)
        }
    }

    @Test fun noRecordReturnsNullWithoutCreatingAnything() = runTest {
        val target = FakeTarget()
        assertNull(CompletedArtifactPublication(temporary.root).recover(taskId) { target })
        assertEquals(0, target.creates)
    }

    @Test fun orphanCreateIsReusedAndOriginalDestinationSnapshotSurvivesRestart() = runTest {
        val file = source(temporary.root)
        val journal = CompletedArtifactPublication(temporary.root)
        journal.write(taskId, artifact(file))
        val target = FakeTarget().apply { failAfterCreate = true }
        assertFailsWith<IllegalStateException> { journal.recover(taskId) { target } }
        assertTrue(file.exists())
        val output = CompletedArtifactPublication(temporary.root).recover(taskId) { mode ->
            assertEquals(StorageMode.SAF, mode); target
        }
        assertEquals("own-final-uri", output?.uri)
        assertEquals("original-tree", target.lastArtifact?.treeUri)
        assertEquals(listOf("drama", "劇名"), target.lastArtifact?.folders)
        assertEquals(1, target.creates)
        assertEquals(1, target.copies)
    }

    @Test fun incompleteCopyKeepsPrivateArtifactAndReusesSameStagingOutput() = runTest {
        val file = source(temporary.root)
        val journal = CompletedArtifactPublication(temporary.root)
        journal.write(taskId, artifact(file))
        val target = FakeTarget().apply { failCopy = true }
        assertFailsWith<IllegalStateException> { journal.recover(taskId) { target } }
        assertFalse(target.visible)
        assertFalse(requireNotNull(journal.read(taskId)).copied)
        assertTrue(file.exists())
        journal.recover(taskId) { target }
        assertEquals(1, target.creates)
        assertEquals(2, target.copies)
        assertEquals(file.readBytes().toList(), target.bytes.toList())
    }

    @Test fun visibilityCrashAndCompletedRetryNeverCopyOrCreateAgain() = runTest {
        val file = source(temporary.root)
        val journal = CompletedArtifactPublication(temporary.root)
        journal.write(taskId, artifact(file))
        val target = FakeTarget().apply { failAfterVisible = true }
        assertFailsWith<IllegalStateException> { journal.recover(taskId) { target } }
        assertTrue(target.visible)
        assertTrue(requireNotNull(journal.read(taskId)).copied)
        assertFalse(requireNotNull(journal.read(taskId)).published)
        val recreated = CompletedArtifactPublication(temporary.root)
        val output = recreated.recover(taskId) { target }
        assertEquals(output, recreated.recover(taskId) { target })
        assertEquals(1, target.creates)
        assertEquals(1, target.copies)
        assertTrue(requireNotNull(journal.read(taskId)).published)
    }

    @Test fun corruptedOrUnownedPrivateArtifactIsNotCopiedAndCannotFallBackToDownload() = runTest {
        val file = source(temporary.root)
        val journal = CompletedArtifactPublication(temporary.root)
        journal.write(taskId, artifact(file))
        file.appendText("corruption")
        val target = FakeTarget()
        assertFailsWith<IllegalStateException> { journal.recover(taskId) { target } }
        assertEquals(0, target.copies)
        val outside = temporary.newFile("unrelated.mp4").apply { writeText("data") }
        journal.write(taskId, artifact(outside))
        assertFailsWith<IllegalArgumentException> { journal.recover(taskId) { target } }
        assertEquals(0, target.copies)
        assertEquals("data", outside.readText())
    }

    @Test fun corruptJournalAndUnsafeTaskIdFailWithoutPublishing() = runTest {
        val file = source(temporary.root)
        val journal = CompletedArtifactPublication(temporary.root)
        journal.write(taskId, artifact(file))
        File(temporary.root, "$taskId/publication.json").writeText("truncated{")
        val target = FakeTarget()
        assertFailsWith<IllegalArgumentException> { journal.recover(taskId) { target } }
        assertFailsWith<IllegalArgumentException> { journal.read("../another-task") }
        assertEquals(0, target.creates)
    }

    @Test fun explicitDeletionCleansOnlyStagingAndFailedCleanupRetainsJournalAndArtifact() = runTest {
        val file = source(temporary.root)
        val journal = CompletedArtifactPublication(temporary.root)
        journal.write(taskId, artifact(file))
        val target = FakeTarget().apply { failCopy = true }
        assertFailsWith<IllegalStateException> { journal.recover(taskId) { target } }
        target.failDiscard = true
        assertFalse(journal.discard(taskId) { target })
        assertTrue(file.exists())
        assertTrue(journal.read(taskId) != null)
        target.failDiscard = false
        assertTrue(journal.discard(taskId) { target })
        assertNull(target.row)
        // Queue owns deletion of journal/private file after this acknowledgment.
        assertTrue(file.exists())
        assertTrue(journal.read(taskId) != null)
    }

    @Test fun explicitDeletionNeverDeletesPublishedOutputEvenAfterVisibilityCrash() = runTest {
        val file = source(temporary.root)
        val journal = CompletedArtifactPublication(temporary.root)
        journal.write(taskId, artifact(file))
        val target = FakeTarget().apply { failAfterVisible = true }
        assertFailsWith<IllegalStateException> { journal.recover(taskId) { target } }
        assertTrue(journal.discard(taskId) { target })
        assertTrue(target.visible)
        assertEquals("own-staging-uri", target.row)
        journal.recover(taskId) { target }
        val previous = target.discards
        assertTrue(journal.discard(taskId) { target })
        assertEquals(previous, target.discards)
        assertTrue(target.visible)
    }
}
