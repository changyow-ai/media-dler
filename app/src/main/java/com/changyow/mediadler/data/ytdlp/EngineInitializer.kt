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

/** Initializes the bundled yt-dlp + ffmpeg runtimes exactly once. */
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
}
