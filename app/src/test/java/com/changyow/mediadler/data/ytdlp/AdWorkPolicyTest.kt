package com.changyow.mediadler.data.ytdlp

import java.io.File
import com.changyow.mediadler.core.model.StorageMode
import com.changyow.mediadler.core.repo.DownloadOutput
import com.changyow.mediadler.data.storage.CompletedArtifact
import com.changyow.mediadler.data.storage.CompletedArtifactPublication
import com.changyow.mediadler.data.storage.PublicationTarget
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdWorkPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun runtimeChangePreservesFrozenPolicyAndExistingMedia() {
        val root = temporary.newFolder()
        val partial = File(root, "media.part").apply { writeText("retained") }
        AdWorkPolicy.requireCompatible(root, "WEIGHTED\nruntime-a")
        AdWorkPolicy.requireCompatible(root, "WEIGHTED\nruntime-a")
        assertFailsWith<IllegalStateException> { AdWorkPolicy.requireCompatible(root, "WEIGHTED\nruntime-b") }
        assertFailsWith<IllegalStateException> { AdWorkPolicy.requireCompatible(root, "OFF") }
        assertEquals("WEIGHTED\nruntime-a", File(root, "ad-execution-policy.txt").readText())
        assertEquals("retained", partial.readText())
        assertEquals(2, root.listFiles()!!.size)
    }

    @Test fun sourceChangeIsReportedAcrossIsolatedChildDirectoriesAndPreservesOldWork() {
        val root = temporary.newFolder()
        val oldWork = File(root, "first-snapshot/media.part").apply {
            parentFile!!.mkdirs(); writeText("old retained bytes")
        }
        assertFalse(AdWorkPolicy.recordSourceIdentity(root, "a".repeat(64)))
        assertFalse(AdWorkPolicy.recordSourceIdentity(root, "a".repeat(64)))
        File(root, "second-snapshot").mkdirs()
        assertTrue(AdWorkPolicy.recordSourceIdentity(root, "b".repeat(64)))
        assertFalse(AdWorkPolicy.recordSourceIdentity(root, "b".repeat(64)))
        assertEquals("old retained bytes", oldWork.readText())
        assertEquals("b".repeat(64), File(root, "ad-source-identity.txt").readText())
    }

    @Test fun explicitFullContentModePreservesFrozenAdPolicyAndUsesSeparateSourceMetadata() {
        val root = temporary.newFolder()
        AdWorkPolicy.requireCompatible(root, "WEIGHTED\nruntime-old")
        val policy = File(root, "ad-execution-policy.txt").readBytes()
        AdWorkPolicy.requireCompatible(root, "WEIGHTED\nno-hls-analysis", skipAdAnalysis = true)
        assertTrue(policy.contentEquals(File(root, "ad-execution-policy.txt").readBytes()))
        val full = AdWorkPolicy.fullContentDirectory(root)
        assertFalse(AdWorkPolicy.recordSourceIdentity(root, "a".repeat(64)))
        assertFalse(AdWorkPolicy.recordSourceIdentity(full, "b".repeat(64)))
        assertEquals("a".repeat(64), File(root, "ad-source-identity.txt").readText())
        assertEquals("b".repeat(64), File(full, "ad-source-identity.txt").readText())
        assertFailsWith<IllegalStateException> { AdWorkPolicy.requireCompatible(root, "OFF\nno-hls-analysis") }
    }

    @Test fun oldFilteredPublicationCannotBeRecoveredOrRepublishedBySkippedTask() {
        val workRoot = temporary.newFolder()
        val task = File(workRoot, "task").apply { mkdirs() }
        val old = File(task, "old-filtered/media.mp4").apply { parentFile!!.mkdirs(); writeText("filtered") }
        val journal = CompletedArtifactPublication(workRoot)
        journal.write("task", artifact(old))
        val saved = File(task, "publication.json").readBytes()
        val target = RecordingTarget()
        assertFailsWith<IllegalStateException> {
            runBlocking {
                AdWorkPolicy.requireRecoveryCompatible(workRoot, "task", skipAdAnalysis = true)
                journal.recover("task") { target }
            }
        }
        assertEquals(0, target.calls)
        assertTrue(saved.contentEquals(File(task, "publication.json").readBytes()))
        assertEquals("filtered", old.readText())
        val misleadingSibling = File(task, AdWorkPolicy.fullContentDirectory(task).name + "-old/media.mp4")
        assertFailsWith<IllegalStateException> { AdWorkPolicy.requireFullContentArtifact(task, misleadingSibling.path) }
        // Ordinary mode keeps the existing publication contract.
        AdWorkPolicy.requireRecoveryCompatible(workRoot, "task", skipAdAnalysis = false)
        assertEquals("content://published", runBlocking { journal.recover("task") { target } }?.uri)
    }

    @Test fun skippedFullContentPublicationStillRecoversAfterRestart() = runBlocking {
        val workRoot = temporary.newFolder()
        val task = File(workRoot, "task").apply { mkdirs() }
        val full = File(AdWorkPolicy.fullContentDirectory(task), "fingerprint/media.mp4").apply {
            parentFile!!.mkdirs(); writeText("original including ads")
        }
        CompletedArtifactPublication(workRoot).write("task", artifact(full))
        AdWorkPolicy.requireRecoveryCompatible(workRoot, "task", skipAdAnalysis = true)
        val target = RecordingTarget()
        val output = CompletedArtifactPublication(workRoot).recover("task") { target }
        assertEquals("content://published", output?.uri)
        assertEquals("original including ads", target.copied)
        assertEquals(3, target.calls)
    }

    @Test fun copiedOrPublishedFilteredJournalCannotBypassFullContentRecoveryGuard() {
        for (published in listOf(false, true)) {
            val workRoot = temporary.newFolder()
            val task = File(workRoot, "task").apply { mkdirs() }
            val filtered = File(task, "old-filtered/media.mp4")
            CompletedArtifactPublication(workRoot).write("task", artifact(filtered).copy(
                uri = "content://existing-filtered", copied = true, published = published))
            val saved = File(task, "publication.json").readBytes()
            val target = RecordingTarget()
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    AdWorkPolicy.requireRecoveryCompatible(workRoot, "task", skipAdAnalysis = true)
                    CompletedArtifactPublication(workRoot).recover("task") { target }
                }
            }
            assertEquals(0, target.calls)
            assertTrue(saved.contentEquals(File(task, "publication.json").readBytes()))
        }
    }

    @Test fun fullContentPublicationMustRemainStrictlyInsideItsDedicatedNamespace() {
        val task = temporary.newFolder()
        val full = AdWorkPolicy.fullContentDirectory(task).apply { mkdirs() }
        val original = File(full, "fingerprint/media.mp4").apply { parentFile!!.mkdirs(); writeText("complete") }
        AdWorkPolicy.requireFullContentArtifact(task, original.path)
        assertFailsWith<IllegalStateException> { AdWorkPolicy.requireFullContentArtifact(task, full.path) }
        assertFailsWith<IllegalStateException> {
            AdWorkPolicy.requireFullContentArtifact(task, File(full, "../filtered/media.mp4").path)
        }
    }

    private fun artifact(file: File) = CompletedArtifact(file.canonicalPath, file.length(),
        StorageMode.DOWNLOADS, null, listOf("Downloads"), "test.mp4", "video/mp4", "owned-token")

    private class RecordingTarget : PublicationTarget {
        var calls = 0
        var copied: String? = null
        override suspend fun findOrCreate(artifact: CompletedArtifact): String { calls++; return "content://published" }
        override suspend fun copy(artifact: CompletedArtifact, uri: String, source: File) { calls++; copied = source.readText() }
        override suspend fun finish(artifact: CompletedArtifact, uri: String): DownloadOutput {
            calls++
            return DownloadOutput(uri, artifact.displayName, artifact.mimeType)
        }
        override fun discard(artifact: CompletedArtifact) = false
    }
}
