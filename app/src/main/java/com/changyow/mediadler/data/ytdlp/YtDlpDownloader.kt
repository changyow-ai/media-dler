package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.download.DownloadPlanner
import com.changyow.mediadler.core.download.HlsNetworkRetry
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.SourceKind
import com.changyow.mediadler.core.model.DownloadPhase
import com.changyow.mediadler.core.model.DownloadProgress
import com.changyow.mediadler.core.download.YtDlpProgressParser
import com.changyow.mediadler.core.repo.DownloadOutput
import com.changyow.mediadler.core.repo.Downloader
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.data.storage.DownloadPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class YtDlpDownloader(
    private val engine: EngineInitializer,
    private val publisher: DownloadPublisher,
    private val workRoot: File,
    private val settingsRepository: SettingsRepository,
) : Downloader {

    override suspend fun recover(processId: String, onProgress: (DownloadProgress) -> Unit): Result<DownloadOutput?> =
        try {
            val recovered = publisher.recover(processId) {
                onProgress(DownloadProgress(DownloadPhase.SAVING, detail = "正在恢復儲存已完成的媒體"))
            }
            // The runner calls recover before page resolution or starting a native download.
            // An existing publication retains its own destination and bypasses this check.
            if (recovered == null) publisher.requireWritableDestination()
            Result.success(recovered)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }

    override suspend fun download(
        request: DownloadRequest,
        processId: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<DownloadOutput> = withContext(Dispatchers.IO) {
        val state = engine.ensureInit()
        if (state is EngineState.Failed) {
            return@withContext Result.failure(IllegalStateException(state.message))
        }

        require(processId.matches(Regex("[A-Za-z0-9_-]+")))
        val originalPlan = DownloadPlanner.plan(request)
        val hls = if (request.item.source.kind == SourceKind.GENERAL) null
            else SpecialSourceResume.prepare(originalPlan.resource)
        // Unsupported special sources use a fresh work directory on every attempt.
        // A bounded worker must require foreground continuation if it interrupts one.
        onProgress(DownloadProgress(DownloadPhase.DOWNLOADING,
            canResume = request.item.source.kind == SourceKind.GENERAL || hls != null))
        // Download the exact child whose snapshot we validated, rather than selecting
        // a potentially different variant from the master again on each attempt.
        val plan = hls?.let { originalPlan.copy(resource = it.resource) } ?: originalPlan
        // A different resource/format cannot inherit incompatible partial files. Old attempts
        // remain under this stable task directory until completion or explicit deletion.
        val resume = if (request.item.source.kind == SourceKind.GENERAL) ""
            else hls?.let { "hls-checkpoint-v1\n${it.identity}" } ?: UUID.randomUUID().toString()
        val identity = plan.resource.url + "\n" + plan.formatArguments.joinToString("\n") +
            if (resume.isEmpty()) "" else "\n$resume"
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val workDir = File(File(workRoot, processId), fingerprint).apply { mkdirs() }
        try {
            val hlsSession = hls?.let { HlsResumeFiles.writeSession(workDir, it) }
            // Rate changes apply on the next start/manual retry. Keep this out of the
            // resource fingerprint so changing the cap never discards resumable data.
            val rateLimit = settingsRepository.settings.first().downloadRateLimitBytesPerSecond
            val ytRequest = plan.toYoutubeDLRequest(rateLimit).apply {
                addOption("-o", File(workDir, "media.%(ext)s").absolutePath)
                addOption("--no-mtime")
                addOption("--no-warnings")
                addOption("--newline")
                addOption("--progress")
                addOption("--no-colors")
                addOption("--concurrent-fragments", "1")
                addOption("--abort-on-unavailable-fragments")
                addOption("--retries", "3")
                addOption("--fragment-retries", "3")
                addOption("--extractor-retries", "2")
                addOption("--file-access-retries", "2")
                addOption("--socket-timeout", "20")
                if (hlsSession != null) addOption("--hls-prefer-native")
                addCommands(listOf("--progress-template", YtDlpProgressParser.DOWNLOAD_TEMPLATE,
                    "--progress-template", YtDlpProgressParser.POSTPROCESS_TEMPLATE))
            }

            suspend fun executeOnce() {
                engine.executeDownload(ytRequest, hlsSession) { line ->
                    YtDlpProgressParser.parse(line)?.let(onProgress)
                }
            }
            if (hlsSession == null) executeOnce() else HlsNetworkRetry.execute(
                onRetry = { attempt, wait ->
                    onProgress(DownloadProgress(DownloadPhase.DOWNLOADING,
                        detail = "網路暫時中斷，${wait / 1000} 秒後接續已下載片段（第 $attempt 次重試）"))
                }, download = { executeOnce() },
            )

            val produced = selectOutput(workDir, request.selection)
                ?: return@withContext Result.failure(IllegalStateException("下載完成但找不到輸出檔"))

            onProgress(DownloadProgress(DownloadPhase.SAVING, detail = "正在儲存完成的媒體"))
            Result.success(publisher.publish(processId, produced, request))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(t)
        }
    }

    /** Picks the real output file, matching the requested kind rather than blindly the largest. */
    private fun selectOutput(workDir: File, selection: FormatSelection): File? {
        // Control files and retained fragment files are never publishable media.
        val files = workDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("media.") &&
                !it.name.contains("-Frag") && it.extension.lowercase() !in TEMP_EXTS }
        if (files.isEmpty()) return null
        return when (selection) {
            is FormatSelection.Audio ->
                files.firstOrNull { it.extension.equals(selection.audioFormat.ext, ignoreCase = true) }
                    ?: files.firstOrNull { it.extension.lowercase() in AUDIO_EXTS }
                    ?: files.maxByOrNull { it.length() }
            FormatSelection.ImageOriginal ->
                files.firstOrNull { it.extension.lowercase() in IMAGE_EXTS }
                    ?: files.maxByOrNull { it.length() }
            else -> files.maxByOrNull { it.length() }
        }
    }

    private companion object {
        val TEMP_EXTS = setOf("part", "ytdl", "tmp", "temp")
        val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "opus", "ogg", "wav", "flac")
        val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    }
}
