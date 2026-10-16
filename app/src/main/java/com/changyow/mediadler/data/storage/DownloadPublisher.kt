package com.changyow.mediadler.data.storage

import android.os.Build
import com.changyow.mediadler.core.download.DownloadDestinationPolicy
import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.StorageMode
import com.changyow.mediadler.core.repo.DownloadOutput
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.util.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Retry publication before resolution/download; an existing record is always authoritative. */
class DownloadPublisher(
    private val settings: SettingsRepository,
    private val mediaStore: MediaStoreStorage,
    private val saf: SafStorage,
    private val workRoot: File,
) {
    private val journal = CompletedArtifactPublication(workRoot)
    private val mutex = Mutex()

    /** Queue invokes only after native/publication acknowledgement; never waits for an active publisher. */
    fun discard(taskId: String): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            runCatching { journal.discard(taskId, ::target) }.getOrDefault(false)
        } finally {
            mutex.unlock()
        }
    }

    suspend fun recover(taskId: String, onRecovering: () -> Unit = {}): DownloadOutput? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (journal.read(taskId) == null) null else {
                onRecovering()
                journal.recover(taskId, ::target)
            }
        }
    }

    /** Validate before network work, and recheck before committing a new destination. */
    internal suspend fun requireWritableDestination(): AppSettings = withContext(Dispatchers.IO) {
        settings.settings.first().also { current ->
            if (current.storageMode == StorageMode.SAF) saf.requireWritableTree(current.safTreeUri)
            else check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Android 9 請到設定選擇儲存資料夾後重試"
            }
        }
    }

    suspend fun publish(taskId: String, sourceFile: File, request: DownloadRequest): DownloadOutput =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (journal.read(taskId) == null) {
                    require(sourceFile.canonicalFile.toPath().startsWith(File(workRoot, taskId).canonicalFile.toPath()))
                    check(sourceFile.isFile && sourceFile.length() > 0) { "下載完成檔案無效" }
                    RandomAccessFile(sourceFile, "rw").use { it.fd.sync() }
                    val ext = if (request.selection == FormatSelection.ImageOriginal &&
                        sourceFile.extension.lowercase() !in IMAGE_EXTENSIONS
                    ) MimeTypes.imageExtensionFromContent(sourceFile) ?: error("下載的內容不是支援的圖片")
                    else sourceFile.extension.ifBlank { "bin" }
                    val destination = DownloadDestinationPolicy.destination(request.item, ext)
                    // Do not persist an unusable destination: retry must be able to pick up
                    // the folder chosen after an initial missing/revoked SAF permission.
                    val current = requireWritableDestination()
                    val token = MessageDigest.getInstance("SHA-256").digest(taskId.toByteArray())
                        .joinToString("") { "%02x".format(it) }.take(24)
                    // SAF rename may change the document URI. Its final token also closes
                    // the rename-before-journal crash window without claiming unrelated files.
                    val name = if (current.storageMode == StorageMode.SAF)
                        tokenName(destination.displayName, token) else destination.displayName
                    journal.write(taskId, CompletedArtifact(sourceFile.canonicalPath, sourceFile.length(),
                        current.storageMode, current.safTreeUri, destination.folders,
                        name, MimeTypes.fromExtension(ext), token))
                }
                requireNotNull(journal.recover(taskId, ::target))
            }
        }

    private fun target(mode: StorageMode): PublicationTarget = when (mode) {
        StorageMode.DOWNLOADS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStore.publicationTarget()
        } else error("舊有 Downloads 任務無法在 Android 9 繼續，請選擇資料夾後重新加入下載")
        StorageMode.SAF -> saf.publicationTarget
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
        fun tokenName(name: String, token: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} [$token]${name.substring(dot)}" else "$name [$token]"
        }
    }
}
