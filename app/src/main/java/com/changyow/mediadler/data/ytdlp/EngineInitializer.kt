package com.changyow.mediadler.data.ytdlp

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
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
        runCatching {
            when (YoutubeDL.getInstance().updateYoutubeDL(application, channel)) {
                YoutubeDL.UpdateStatus.DONE -> "已更新 yt-dlp 至 ${version() ?: "最新版"}"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "已是最新（yt-dlp ${version() ?: "?"}）"
                else -> "yt-dlp ${version() ?: ""}"
            }
        }
    }
}
