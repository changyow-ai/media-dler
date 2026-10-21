package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.data.storage.CompletedArtifactPublication
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Frozen ad decisions and explicit full-content attempts retain separate work and publication. */
internal object AdWorkPolicy {
    private const val FULL_CONTENT_DIRECTORY = "full-content-no-ad-analysis-v1"

    fun fullContentDirectory(taskDirectory: File): File = File(taskDirectory, FULL_CONTENT_DIRECTORY)

    fun requireRecoveryCompatible(workRoot: File, taskId: String, skipAdAnalysis: Boolean) {
        if (skipAdAnalysis) CompletedArtifactPublication(workRoot).read(taskId)?.let { artifact ->
            requireFullContentArtifact(File(workRoot, taskId), artifact.sourcePath)
        }
    }

    /** A skipped task can recover only publications produced by its isolated full-content attempt. */
    fun requireFullContentArtifact(taskDirectory: File, sourcePath: String) {
        val directory = fullContentDirectory(taskDirectory)
        val root = directory.canonicalFile.toPath()
        val source = File(sourcePath).canonicalFile.toPath()
        check(!Files.isSymbolicLink(directory.toPath()) && source != root && source.startsWith(root)) {
            "略過分析的任務不能恢復先前去廣告的成品，已保留原暫存與儲存紀錄"
        }
    }

    fun requireCompatible(taskDirectory: File, identity: String, skipAdAnalysis: Boolean = false) {
        // Explicitly downloading the original content must neither reuse nor rewrite frozen ad decisions.
        if (skipAdAnalysis) return
        val target = File(taskDirectory, "ad-execution-policy.txt")
        val bytes = identity.toByteArray(Charsets.UTF_8)
        if (target.exists()) {
            check(target.isFile && target.length() <= 4096 && target.readBytes().contentEquals(bytes)) {
                "去 AD 分析版本已變更，已保留原任務與暫存；請重新建立下載"
            }
            return
        }
        taskDirectory.mkdirs()
        atomicWrite(target, bytes)
    }

    /** Task-level notice: changed snapshots select a different child work directory. */
    fun recordSourceIdentity(taskDirectory: File, sourceIdentity: String): Boolean {
        require(sourceIdentity.matches(Regex("[0-9a-f]{64}")))
        val target = File(taskDirectory, "ad-source-identity.txt")
        val previous = target.takeIf { it.isFile && it.length() == 64L }?.readText()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (previous != sourceIdentity) {
            taskDirectory.mkdirs()
            atomicWrite(target, sourceIdentity.toByteArray(Charsets.UTF_8), replace = true)
        }
        return previous != null && previous != sourceIdentity
    }

    private fun atomicWrite(target: File, bytes: ByteArray, replace: Boolean = false) {
        val temporary = File.createTempFile("ad-metadata-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            val options = if (replace) arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                else arrayOf(StandardCopyOption.ATOMIC_MOVE)
            Files.move(temporary.toPath(), target.toPath(), *options)
        } finally { temporary.delete() }
    }
}
