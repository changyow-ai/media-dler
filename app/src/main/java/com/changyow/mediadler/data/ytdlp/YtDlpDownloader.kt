package com.changyow.mediadler.data.ytdlp

import android.content.Context
import com.changyow.mediadler.core.download.FormatSelector
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.StorageMode
import com.changyow.mediadler.core.repo.DownloadOutput
import com.changyow.mediadler.core.repo.Downloader
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.data.storage.MediaStoreStorage
import com.changyow.mediadler.data.storage.SafStorage
import com.changyow.mediadler.util.FileNames
import com.changyow.mediadler.util.MimeTypes
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class YtDlpDownloader(
    private val context: Context,
    private val engine: EngineInitializer,
    private val settings: SettingsRepository,
    private val mediaStore: MediaStoreStorage,
    private val saf: SafStorage,
) : Downloader {

    override suspend fun download(
        request: DownloadRequest,
        processId: String,
        onProgress: (Float, String) -> Unit,
    ): Result<DownloadOutput> = withContext(Dispatchers.IO) {
        val state = engine.ensureInit()
        if (state is EngineState.Failed) {
            return@withContext Result.failure(IllegalStateException(state.message))
        }

        val item = request.item
        val workDir = File(context.cacheDir, "downloads/${System.nanoTime()}").apply { mkdirs() }
        try {
            val ytRequest = YoutubeDLRequest(item.sourceUrl).apply {
                addOption("-o", File(workDir, "media.%(ext)s").absolutePath)
                addOption("--no-mtime")
                addOption("--no-warnings")
                if (item.playlistIndex != null) {
                    addOption("--playlist-items", item.playlistIndex.toString())
                } else {
                    addOption("--no-playlist")
                }
                addCommands(FormatSelector.args(request.selection))
            }

            YoutubeDL.getInstance().execute(ytRequest, processId) { progress, _, line ->
                if (progress >= 0f) onProgress(progress / 100f, line)
            }

            val produced = workDir.walkTopDown().filter { it.isFile }.maxByOrNull { it.length() }
                ?: return@withContext Result.failure(IllegalStateException("下載完成但找不到輸出檔"))

            val ext = produced.extension.ifBlank { "bin" }
            val baseName = FileNames.sanitize(item.title).ifBlank { item.id.ifBlank { "media" } }
            val displayName = "$baseName.$ext"
            val mime = MimeTypes.fromExtension(ext)

            val current = settings.settings.first()
            val uri = when (current.storageMode) {
                StorageMode.SAF -> saf.save(produced, displayName, mime, current.safTreeUri)
                StorageMode.DOWNLOADS -> mediaStore.save(produced, displayName, mime)
            }
            Result.success(DownloadOutput(uri.toString(), displayName, mime))
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            workDir.deleteRecursively()
        }
    }
}
