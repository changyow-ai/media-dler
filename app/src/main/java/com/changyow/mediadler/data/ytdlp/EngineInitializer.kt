package com.changyow.mediadler.data.ytdlp

import android.app.Application
import com.changyow.mediadler.core.transcribe.FfmpegPcm
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface EngineState {
    data object Initializing : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

/** Initializes and keeps the bundled yt-dlp + ffmpeg runtimes up to date. */
class EngineInitializer(private val application: Application) {

    private val _state = MutableStateFlow<EngineState>(EngineState.Initializing)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val runtime = EngineRuntimeAccess()

    suspend fun ensureInit(): EngineState = mutex.withLock {
        if (_state.value is EngineState.Ready) return EngineState.Ready
        try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
            }
            _state.value = EngineState.Ready
        } catch (t: Throwable) {
            _state.value = EngineState.Failed(t.message ?: "yt-dlp 初始化失敗")
        }
        _state.value
    }

    /** Current bundled yt-dlp version (null if not yet initialized). */
    fun version(): String? = runCatching { YoutubeDL.getInstance().version(application) }.getOrNull()

    /** Blocking call; callers use runInterruptible so waiting/running work can be cancelled. */
    fun execute(
        request: YoutubeDLRequest,
        processId: String = UUID.randomUUID().toString(),
        callback: (Float, Long, String) -> Unit = { _, _, _ -> },
    ): YoutubeDLResponse = runtime.execute {
        try {
            YoutubeDL.getInstance().execute(request, processId, callback)
        } catch (error: Throwable) {
            // Clean up before releasing access to the archive. The SDK also destroys
            // its native process when execute is interrupted.
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw error
        }
    }

    /** Download-only supervision; archive access lasts through native exit and output-reader cleanup. */
    suspend fun executeDownload(
        request: YoutubeDLRequest,
        hlsResumeSession: File? = null,
        onLine: (String) -> Unit,
    ) {
        executeNative { paths, process ->
            if (!request.hasOption("--cache-dir") || request.getOption("--cache-dir") == null) {
                request.addOption("--no-cache-dir")
            }
            request.addOption("--ffmpeg-location", paths.ffmpeg.absolutePath)
            request.addOption("--js-runtimes", "quickjs:${paths.quickJs.absolutePath}")
            if ("libaria2c.so" in request.buildCommand()) {
                request.addOption("--external-downloader-args", "aria2c:--summary-interval=1")
                request.addOption("--external-downloader-args", "aria2c:--ca-certificate=${paths.certificate.absolutePath}")
            }
            val entry = if (hlsResumeSession == null) listOf(paths.ytdlp.absolutePath) else {
                val adapter = File(hlsResumeSession.parentFile, "runner.py")
                application.assets.open("hls_resume.py").use { input ->
                    adapter.outputStream().use { output -> input.copyTo(output) }
                }
                application.assets.open("hls_media_validation.py").use { input ->
                    File(hlsResumeSession.parentFile, "hls_media_validation.py").outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                listOf(adapter.absolutePath, "--spec", hlsResumeSession.absolutePath,
                    "--ytdlp", paths.ytdlp.absolutePath, "--validation-ffmpeg", paths.ffmpeg.absolutePath)
            }
            process.execute(entry + request.buildCommand(), onLine)
        }
    }

    private fun weightedAdAssets(): Map<String, ByteArray> = WeightedHlsAds.ASSETS.associateWith { name ->
        application.assets.open(name).use { it.readBytes() }
    }

    /** Classifier, OCR adapter and catalog changes isolate both decisions and checkpoints. */
    internal fun hlsAdPolicyIdentity(knownCatalogEnabled: Boolean, weightedEnabled: Boolean = true): String {
        val assets = weightedAdAssets()
        return if (weightedEnabled) WeightedHlsAds.identity(WeightedHlsAds.policyBytes(assets, knownCatalogEnabled))
        else EightMapleHlsAds.policyIdentity(assets.getValue(EightMapleHlsAds.CATALOG_ASSET)) + "\n" +
            WeightedHlsAds.identity(assets.getValue("hls_ad_filter.py"))
    }

    internal suspend fun prepareHlsAds(
        workDir: File,
        original: PreparedHlsResume,
        rateLimit: Long?,
        knownCatalogEnabled: Boolean,
        weightedEnabled: Boolean = true,
        onStage: (String) -> Unit,
    ): PreparedHlsResume = withContext(Dispatchers.IO) {
        val files = if (weightedEnabled) WeightedHlsAds.files(workDir) else EightMapleHlsAds.files(workDir)
        val assets = weightedAdAssets()
        files.writeInput(original, if (weightedEnabled) WeightedHlsAds.policyBytes(assets, knownCatalogEnabled)
            else assets.getValue(EightMapleHlsAds.CATALOG_ASSET),
            rateLimit, knownCatalogEnabled.takeIf { weightedEnabled })
        assets.forEach { (name, bytes) -> File(files.directory, name).writeBytes(bytes) }
        executeNative { paths, process ->
            check(paths.ffprobe.isFile && paths.ffprobe.canExecute()) { "影片檢查引擎不完整" }
            if (!weightedEnabled) {
                process.execute(listOf(files.script.absolutePath, "--input", files.input.absolutePath,
                    "--catalog", files.catalog.absolutePath, "--plan", files.plan.absolutePath,
                    "--ffmpeg", paths.ffmpeg.absolutePath, "--ffprobe", paths.ffprobe.absolutePath)) { line ->
                    if (line.startsWith("HLS_AD_")) onStage(line)
                }
            } else AndroidAdOcr(files.directory).use { ocr ->
                process.execute(listOf(File(files.directory, "weighted_hls_ads.py").absolutePath,
                    "--input", files.input.absolutePath,
                    "--catalog", File(files.directory, EightMapleHlsAds.CATALOG_ASSET).absolutePath,
                    "--policy", files.catalog.absolutePath, "--plan", files.plan.absolutePath,
                    "--ffmpeg", paths.ffmpeg.absolutePath, "--ffprobe", paths.ffprobe.absolutePath)) { line ->
                    if (!ocr.handle(line) && (line.startsWith("HLS_AD_STAGE ") ||
                        line.startsWith("HLS_AD_CANDIDATE ") || line.startsWith("HLS_AD_PROGRESS "))) onStage(line)
                }
            }
            files.readResult(original)
        }
    }

    /** A filtered playlist is not a completed, playable publication until this succeeds. */
    internal suspend fun validateHlsMedia(source: File, hls: PreparedHlsResume, audioOnly: Boolean) = executeNative { paths, process ->
        val directory = requireNotNull(hls.adPlan).parentFile
        val validation = File(directory, "media-validation.json")
        val expected = Regex("(?m)^#EXTINF:([0-9.]+),").findAll(hls.manifest)
            .sumOf { it.groupValues[1].toDouble() }
        process.execute(listOf(File(directory, "weighted_hls_ads.py").absolutePath,
            "--validate-media", source.absolutePath, "--expected-duration", expected.toString(),
            "--validation-result", validation.absolutePath,
            "--validation-session", File(directory, "session.json").absolutePath,
            "--ffmpeg", paths.ffmpeg.absolutePath, "--ffprobe", paths.ffprobe.absolutePath) +
            if (audioOnly) listOf("--audio-only") else emptyList()) {}
        check(validation.isFile && validation.length() in 1..65_536) { "去廣告影片驗證未完成" }
        val value = Json.parseToJsonElement(validation.readText()).jsonObject
        check(value["status"]?.jsonPrimitive?.content == "valid" &&
            value["media_size"]?.jsonPrimitive?.longOrNull == source.length() &&
            value["media_mode"]?.jsonPrimitive?.content == (if (audioOnly) "audio" else "video") &&
            value["full_av_decode"]?.jsonPrimitive?.booleanOrNull == true &&
            value["packet_dts_monotonic"]?.jsonPrimitive?.booleanOrNull == true) { "去廣告影片驗證未通過" }
    }

    /** Native children share download cancellation and finish before a result can be published. */
    suspend fun cleanKnownAds(source: File, onStage: (String) -> Unit = {}): AdCleanupResult = withContext(Dispatchers.IO) {
        val files = AdCleanupFiles.create(source)
        try {
            val result = executeNative { paths, process ->
                check(paths.ffprobe.isFile && paths.ffprobe.canExecute()) { "影片檢查引擎不完整" }
                application.assets.open("ad_cleanup.py").use { input ->
                    files.script.outputStream().use { output -> input.copyTo(output) }
                }
                application.assets.open("known_ads.json").use { input ->
                    files.signatures.outputStream().use { output -> input.copyTo(output) }
                }
                process.execute(listOf(files.script.absolutePath,
                    "--input", source.absolutePath, "--output", files.output.absolutePath,
                    "--result", files.result.absolutePath, "--signatures", files.signatures.absolutePath,
                    "--ffmpeg", paths.ffmpeg.absolutePath, "--ffprobe", paths.ffprobe.absolutePath)) { line ->
                    if (line.startsWith("AD_CLEANUP_STAGE ")) {
                        val stage = line.removePrefix("AD_CLEANUP_STAGE ")
                        if (stage in setOf("scan", "probe", "remux", "validate")) onStage(stage)
                    }
                }
                files.readResult()
            }
            // A cleaned artifact stays with the task's durable publication journal.
            if (result.removedAds == 0) files.directory.deleteRecursively()
            result
        } catch (error: Throwable) {
            // executeNative has acknowledged native exit before any staged files are removed.
            files.directory.deleteRecursively()
            throw error
        }
    }

    /**
     * Decodes a local audio window with the already-bundled FFmpeg, bypassing platform extractor and
     * codec bugs. The caller owns the temporary output, timeout, and bounded PCM read. Native process
     * supervision retains ownership until cancellation has stopped FFmpeg and its output readers.
     */
    suspend fun decodeAudioRange(input: File, output: File, startMs: Long, endMs: Long) {
        require(input.isFile && input.canRead()) { "無法讀取轉錄音訊" }
        require(output.parentFile?.isDirectory == true) { "PCM 暫存目錄不存在" }
        val arguments = FfmpegPcm.arguments(input, output, startMs, endMs)
        executeNative { paths, process ->
            check(paths.ffmpeg.canExecute()) { "音訊解碼引擎不完整" }
            // No shell and no interpolated code: source/output paths are positional arguments.
            process.execute(listOf("-c", "import os, sys; os.execv(sys.argv[1], sys.argv[1:])",
                paths.ffmpeg.absolutePath) + arguments) {}
        }
    }

    private data class NativePaths(
        val python: File,
        val pythonHome: File,
        val certificate: File,
        val ffmpeg: File,
        val ffprobe: File,
        val quickJs: File,
        val ytdlp: File,
        val environment: Map<String, String>,
    )

    private fun nativePaths(): NativePaths {
        // Pinned SDK 0.18.1 layout, derived from public constants and Android context dirs.
        val base = File(application.noBackupFilesDir, YoutubeDL.baseName)
        val packages = File(base, "packages")
        val binaries = File(application.applicationInfo.nativeLibraryDir)
        val pythonHome = File(packages, "python/usr")
        val certificate = File(pythonHome, "etc/tls/cert.pem")
        return NativePaths(
            File(binaries, "libpython.so"), pythonHome, certificate,
            File(binaries, "libffmpeg.so"), File(binaries, "libffprobe.so"),
            File(binaries, "libqjs.so"), File(File(base, YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin),
            mapOf(
                "LD_LIBRARY_PATH" to listOf("python", "ffmpeg", "aria2c")
                    .joinToString(":") { File(packages, "$it/usr/lib").absolutePath },
                "SSL_CERT_FILE" to certificate.absolutePath,
                "PATH" to listOfNotNull(System.getenv("PATH"), binaries.absolutePath).joinToString(":"),
                "PYTHONHOME" to pythonHome.absolutePath,
                "HOME" to pythonHome.absolutePath,
                "TMPDIR" to application.cacheDir.absolutePath,
            ),
        )
    }

    private suspend fun <T> executeNative(work: (NativePaths, NativeDownloadProcess) -> T): T {
        val initialized = ensureInit()
        currentCoroutineContext().ensureActive()
        check(initialized is EngineState.Ready) {
            "引擎初始化失敗：${(initialized as? EngineState.Failed)?.message.orEmpty()}"
        }
        val paths = nativePaths()
        val process = NativeDownloadProcess(paths.python, paths.environment)
        // A caller's canceled job must not release the archive while its native writers survive.
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val execution = workerScope.async {
            runtime.execute {
                check(paths.python.isFile && paths.python.canExecute() && paths.ytdlp.isFile && paths.pythonHome.isDirectory &&
                    paths.certificate.isFile && paths.ffmpeg.isFile && paths.quickJs.isFile) {
                    "下載引擎檔案不完整，請重新啟動 App 初始化引擎"
                }
                work(paths, process)
            }
        }
        try {
            return execution.await()
        } catch (cancelled: CancellationException) {
            process.cancel()
            withContext(NonCancellable) {
                try { execution.await() } catch (_: Throwable) { /* Preserve caller cancellation after native cleanup. */ }
            }
            throw cancelled
        } finally {
            workerScope.cancel()
        }
    }

    /**
     * Updates the bundled yt-dlp. The shipped copy goes stale quickly (YouTube in
     * particular breaks yt-dlp often), so refreshing it is what keeps downloads working.
     */
    suspend fun update(
        channel: YoutubeDL.UpdateChannel = YoutubeDL.UpdateChannel._STABLE,
    ): Result<String> = withContext(Dispatchers.IO) {
        val state = ensureInit()
        if (state is EngineState.Failed) {
            return@withContext Result.failure(IllegalStateException("引擎初始化失敗：${state.message}"))
        }
        val context = currentCoroutineContext()
        try {
            Result.success(runtime.update {
                context.ensureActive()
                when (YoutubeDL.getInstance().updateYoutubeDL(application, channel)) {
                    YoutubeDL.UpdateStatus.DONE -> "已更新 yt-dlp 至 ${version() ?: "最新版"}"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "已是最新（yt-dlp ${version() ?: "?"}）"
                    else -> "yt-dlp ${version() ?: ""}"
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
