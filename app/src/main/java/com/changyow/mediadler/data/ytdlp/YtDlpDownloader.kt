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
        val snapshot = if (request.item.source.kind == SourceKind.GENERAL) null
            else SpecialSourceResume.prepare(originalPlan.resource)
        val adPolicy = if (snapshot != null && request.item.source.kind == SourceKind.EIGHT_MAPLE)
            engine.hlsAdPolicyIdentity() else null
        // Unsupported special sources use a fresh work directory on every attempt.
        // A bounded worker must require foreground continuation if it interrupts one.
        onProgress(DownloadProgress(DownloadPhase.DOWNLOADING,
            canResume = request.item.source.kind == SourceKind.GENERAL || snapshot != null))
        // Download the exact child whose snapshot we validated, rather than selecting
        // a potentially different variant from the master again on each attempt.
        val plan = snapshot?.let { originalPlan.copy(resource = it.resource) } ?: originalPlan
        // A different resource/format cannot inherit incompatible partial files. Old attempts
        // remain under this stable task directory until completion or explicit deletion.
        val resume = if (request.item.source.kind == SourceKind.GENERAL) ""
            else snapshot?.let { "hls-checkpoint-v1\n${it.identity}" +
                (adPolicy?.let { policy -> "\n$policy" } ?: "") } ?: UUID.randomUUID().toString()
        val identity = plan.resource.url + "\n" + plan.formatArguments.joinToString("\n") +
            if (resume.isEmpty()) "" else "\n$resume"
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val workDir = File(File(workRoot, processId), fingerprint).apply { mkdirs() }
        try {
            // The same cap covers candidate traffic and the remaining download. A cap
            // change on retry does not alter the frozen media/checkpoint identity.
            val rateLimit = settingsRepository.settings.first().downloadRateLimitBytesPerSecond
            val hls = if (adPolicy != null) {
                onProgress(DownloadProgress(DownloadPhase.DOWNLOADING,
                    detail = "正在驗證廣告候選片段", canResume = true))
                engine.prepareHlsAds(workDir, requireNotNull(snapshot), rateLimit) { line ->
                    val detail = if (line.startsWith("HLS_AD_CANDIDATE "))
                        "正在驗證廣告候選片段（${line.removePrefix("HLS_AD_CANDIDATE ").replace(' ', '/')}）"
                    else "正在驗證廣告候選片段"
                    onProgress(DownloadProgress(DownloadPhase.DOWNLOADING, detail = detail, canResume = true))
                }
            } else snapshot
            hls?.takeIf { it.skippedAds > 0 }?.let {
                onProgress(DownloadProgress(DownloadPhase.DOWNLOADING,
                    detail = "已辨識 ${it.skippedAds} 段廣告，下載時略過", canResume = true))
            }
            val hlsSession = hls?.let { HlsResumeFiles.writeSession(workDir, it) }
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
                        detail = "來源連線暫時失敗，${wait / 1000} 秒後接續已下載片段（第 $attempt 次重試）"))
                }, download = { executeOnce() },
            )

            val produced = selectOutput(workDir, request.selection)
                ?: return@withContext Result.failure(IllegalStateException("下載完成但找不到輸出檔"))

            val cleaned = if (request.item.source.kind == SourceKind.EIGHT_MAPLE &&
                produced.extension.equals("mp4", ignoreCase = true)) {
                onProgress(DownloadProgress(DownloadPhase.PROCESSING, detail = "正在檢查影片中的已知廣告"))
                engine.cleanKnownAds(produced) { stage ->
                    val detail = when (stage) {
                        "probe" -> "正在確認廣告片段"
                        "remux" -> "正在移除已辨識廣告"
                        "validate" -> "正在驗證處理後的影片"
                        else -> "正在檢查影片中的已知廣告"
                    }
                    onProgress(DownloadProgress(DownloadPhase.PROCESSING, detail = detail))
                }
            } else AdCleanupResult(produced, 0, 0.0)
            cleaned.useForPublication(produced, { publisher.hasPublicationRecord(processId) }) { file ->
                val saving = when {
                    hls != null && hls.skippedAds > 0 -> "已略過 ${hls.skippedAds} 段廣告" +
                        (if (cleaned.removedAds > 0) "、另移除 ${cleaned.removedAds} 段廣告" else "") + "，正在儲存影片"
                    cleaned.removedAds > 0 -> "已移除 ${cleaned.removedAds} 段廣告，正在儲存影片"
                    else -> "正在儲存完成的媒體"
                }
                onProgress(DownloadProgress(DownloadPhase.SAVING, detail = saving))
                Result.success(publisher.publish(processId, file, request))
            }
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
