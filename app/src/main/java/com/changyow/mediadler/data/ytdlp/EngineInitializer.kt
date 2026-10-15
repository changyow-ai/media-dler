package com.changyow.mediadler.data.ytdlp

import android.app.Application
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
    suspend fun executeDownload(request: YoutubeDLRequest, onLine: (String) -> Unit) {
        val initialized = ensureInit()
        currentCoroutineContext().ensureActive()
        check(initialized is EngineState.Ready) {
            "引擎初始化失敗：${(initialized as? EngineState.Failed)?.message.orEmpty()}"
        }
        // Pinned SDK 0.18.1 layout, derived from its public constants and Android context dirs.
        // The SDK exposes no runtime/env getters; its private fields are never accessed.
        val base = File(application.noBackupFilesDir, YoutubeDL.baseName)
        val packages = File(base, "packages")
        val binaries = File(application.applicationInfo.nativeLibraryDir)
        val python = File(binaries, "libpython.so")
        val pythonHome = File(packages, "python/usr")
        val certificate = File(pythonHome, "etc/tls/cert.pem")
        val ffmpeg = File(binaries, "libffmpeg.so")
        val quickJs = File(binaries, "libqjs.so")
        val ytdlp = File(File(base, YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin)
        val environment = mapOf(
            "LD_LIBRARY_PATH" to listOf("python", "ffmpeg", "aria2c")
                .joinToString(":") { File(packages, "$it/usr/lib").absolutePath },
            "SSL_CERT_FILE" to certificate.absolutePath,
            "PATH" to listOfNotNull(System.getenv("PATH"), binaries.absolutePath).joinToString(":"),
            "PYTHONHOME" to pythonHome.absolutePath,
            "HOME" to pythonHome.absolutePath,
            "TMPDIR" to application.cacheDir.absolutePath,
        )
        val process = NativeDownloadProcess(python, environment)
        // A caller's canceled job must not release the archive while its native writers survive.
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val execution = workerScope.async {
            runtime.execute {
                check(python.isFile && python.canExecute() && ytdlp.isFile && pythonHome.isDirectory &&
                    certificate.isFile && ffmpeg.isFile && quickJs.isFile) {
                    "下載引擎檔案不完整，請重新啟動 App 初始化引擎"
                }
                if (!request.hasOption("--cache-dir") || request.getOption("--cache-dir") == null) {
                    request.addOption("--no-cache-dir")
                }
                request.addOption("--ffmpeg-location", ffmpeg.absolutePath)
                request.addOption("--js-runtimes", "quickjs:${quickJs.absolutePath}")
                if ("libaria2c.so" in request.buildCommand()) {
                    request.addOption("--external-downloader-args", "aria2c:--summary-interval=1")
                    request.addOption("--external-downloader-args", "aria2c:--ca-certificate=${certificate.absolutePath}")
                }
                process.execute(listOf(ytdlp.absolutePath) + request.buildCommand(), onLine)
            }
        }
        try {
            execution.await()
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
