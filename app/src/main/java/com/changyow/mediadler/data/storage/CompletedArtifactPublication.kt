package com.changyow.mediadler.data.storage

import com.changyow.mediadler.core.model.StorageMode
import com.changyow.mediadler.core.repo.DownloadOutput
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel

/** A task-owned artifact and destination snapshot. The queue deletes this only after DB completion. */
@Serializable
internal data class CompletedArtifact(
    val sourcePath: String,
    val size: Long,
    val mode: StorageMode,
    val treeUri: String?,
    val folders: List<String>,
    val displayName: String,
    val mimeType: String,
    val token: String,
    val uri: String? = null,
    val copied: Boolean = false,
    val published: Boolean = false,
)

/** Android adapters implement creation, copying and idempotent finalization separately. */
internal interface PublicationTarget {
    suspend fun findOrCreate(artifact: CompletedArtifact): String
    suspend fun copy(artifact: CompletedArtifact, uri: String, source: File)
    suspend fun finish(artifact: CompletedArtifact, uri: String): DownloadOutput
    /** Only the token-owned, incomplete staging object may be deleted. */
    fun discard(artifact: CompletedArtifact): Boolean
}

internal class CompletedArtifactPublication(private val workRoot: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(taskId: String): CompletedArtifact? {
        val record = recordFile(taskId)
        return if (record.isFile) json.decodeFromString<CompletedArtifact>(record.readText()) else null
    }

    fun discard(taskId: String, target: (StorageMode) -> PublicationTarget): Boolean {
        val artifact = read(taskId) ?: return true
        if (artifact.published) return true
        return target(artifact.mode).discard(artifact)
    }

    fun write(taskId: String, artifact: CompletedArtifact) {
        val target = recordFile(taskId)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "無法保存下載完成紀錄" }
        val temporary = File(target.parentFile, "publication.json.new")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(artifact).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(target.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    suspend fun recover(taskId: String, target: (StorageMode) -> PublicationTarget): DownloadOutput? {
        var artifact = read(taskId) ?: return null
        val destination = target(artifact.mode)
        // Creation intent is already durable. The token locates an orphan if creation was
        // successful immediately before the URI record was written.
        if (artifact.uri == null) {
            artifact = artifact.copy(uri = destination.findOrCreate(artifact))
            write(taskId, artifact)
        }
        val uri = requireNotNull(artifact.uri)
        if (!artifact.copied) {
            val source = File(artifact.sourcePath)
            val taskDir = recordFile(taskId).parentFile!!.canonicalFile
            require(source.canonicalFile.toPath().startsWith(taskDir.toPath())) { "下載完成檔案位置無效" }
            check(source.isFile && source.length() == artifact.size) { "下載完成檔案遺失或損毀，無法發布" }
            destination.copy(artifact, uri, source)
            artifact = artifact.copy(copied = true)
            write(taskId, artifact)
        }
        val output = destination.finish(artifact, uri)
        if (!artifact.published || artifact.uri != output.uri || artifact.displayName != output.displayName) {
            write(taskId, artifact.copy(uri = output.uri, displayName = output.displayName, published = true))
        }
        return output
    }

    private fun recordFile(taskId: String): File {
        require(taskId.matches(Regex("[A-Za-z0-9_-]+")))
        return File(File(workRoot, taskId), "publication.json")
    }
}
