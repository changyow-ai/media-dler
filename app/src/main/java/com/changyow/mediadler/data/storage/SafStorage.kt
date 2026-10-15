package com.changyow.mediadler.data.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.changyow.mediadler.core.repo.DownloadOutput
import java.io.File

/** Saves files into a user-picked directory via the Storage Access Framework. */
class SafStorage(private val context: Context) {

    internal val publicationTarget = object : PublicationTarget {
        override fun discard(artifact: CompletedArtifact): Boolean {
            val tree = artifact.treeUri?.let(Uri::parse) ?: return false
            var dir = DocumentFile.fromTreeUri(context, tree) ?: return false
            check(dir.exists() && dir.canRead()) { "無法讀取 SAF 目錄" }
            for (folder in artifact.folders) {
                val next = dir.findFile(folder) ?: return true
                check(next.isDirectory) { "SAF 目錄無效" }
                dir = next
            }
            val staging = dir.findFile(stagingName(artifact)) ?: return true
            check(staging.isFile) { "SAF 暫存名稱不是檔案" }
            return staging.delete()
        }

        override suspend fun findOrCreate(artifact: CompletedArtifact): String {
            val dir = destinationDirectory(artifact)
            val stagingName = stagingName(artifact)
            dir.findFile(stagingName)?.let {
                check(it.isFile) { "SAF 暫存檔案名稱已被目錄使用" }
                return it.uri.toString()
            }
            // The final name carries a task token because providers may change URI on rename.
            dir.findFile(artifact.displayName)?.let {
                check(it.isFile && it.length() == artifact.size) { "SAF 輸出名稱衝突" }
                return it.uri.toString()
            }
            val document = dir.createFile(artifact.mimeType, stagingName)
                ?: error("無法在所選目錄建立暫存檔案")
            if (document.name != stagingName) {
                document.delete()
                val alternative = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    "請改用 Downloads" else "請重新選擇本機資料夾"
                error("所選 SAF 服務無法保留暫存名稱，$alternative")
            }
            return document.uri.toString()
        }

        override suspend fun copy(artifact: CompletedArtifact, uri: String, source: File) {
            val ownStaging = destinationDirectory(artifact).findFile(stagingName(artifact))
            check(ownStaging != null && ownStaging.isFile && ownStaging.uri.toString() == uri) {
                "SAF 暫存檔案識別不符，拒絕覆寫"
            }
            context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
            } ?: error("無法寫入 SAF 暫存檔案")
        }

        override suspend fun finish(artifact: CompletedArtifact, uri: String): DownloadOutput {
            val dir = destinationDirectory(artifact)
            val final = dir.findFile(artifact.displayName)
            val staging = dir.findFile(stagingName(artifact))
            if (final != null) {
                check(final.isFile && final.length() == artifact.size) { "SAF 輸出檔案不完整或名稱衝突" }
                check(staging == null || staging.uri == final.uri) { "SAF 輸出名稱衝突，保留暫存檔案" }
                return DownloadOutput(final.uri.toString(), requireNotNull(final.name), artifact.mimeType)
            }
            val document = staging ?: error("SAF 暫存檔案已不存在")
            check(document.uri.toString() == uri) { "SAF 暫存檔案識別不符" }
            check(document.isFile && document.length() == artifact.size) { "SAF 暫存檔案尚未完整寫入" }
            check(document.renameTo(artifact.displayName)) {
                "所選 SAF 服務無法重新命名；已保留完成檔案，請恢復存取後重試"
            }
            // A provider can assign a new URI during rename; also recover by final name
            // after a process dies between rename and journal update.
            val renamed = dir.findFile(artifact.displayName) ?: error("無法讀取 SAF 發布檔案")
            check(renamed.isFile && renamed.length() == artifact.size) { "SAF 發布檔案不完整" }
            return DownloadOutput(renamed.uri.toString(), requireNotNull(renamed.name), artifact.mimeType)
        }
    }

    private fun stagingName(artifact: CompletedArtifact): String =
        "media-dler-${artifact.token}.partial.${artifact.displayName.substringAfterLast('.', "bin")}"

    internal fun requireWritableTree(treeUri: String?): DocumentFile {
        val tree = treeUri?.let(Uri::parse) ?: error("請先到設定選擇儲存資料夾後重試")
        val directory = DocumentFile.fromTreeUri(context, tree) ?: error("儲存資料夾無效，請到設定重新選擇")
        check(directory.isDirectory && directory.canRead() && directory.canWrite()) {
            "無法存取儲存資料夾，請到設定重新選擇後重試"
        }
        return directory
    }

    private fun destinationDirectory(artifact: CompletedArtifact): DocumentFile {
        var directory = requireWritableTree(artifact.treeUri)
        for (folder in artifact.folders) {
            val existing = directory.findFile(folder)
            directory = if (existing != null) {
                check(existing.isDirectory) { "SAF 目錄名稱已被檔案使用：$folder" }
                existing
            } else directory.createDirectory(folder) ?: error("無法建立 SAF 子目錄：$folder")
        }
        return directory
    }

    suspend fun save(source: File, displayName: String, mimeType: String, treeUri: String?): Uri =
        withContext(Dispatchers.IO) {
            val dir = requireWritableTree(treeUri)
            // Don't delete a same-named file; let SAF de-dup (avoids data loss).
            val doc = dir.createFile(mimeType, displayName) ?: error("無法在所選目錄建立檔案")
            try {
                context.contentResolver.openOutputStream(doc.uri)?.use { output ->
                    source.inputStream().use { it.copyTo(output) }
                } ?: error("無法寫入所選目錄")
            } catch (failure: Throwable) {
                runCatching { doc.delete() }
                throw failure
            }
            doc.uri
        }
}
