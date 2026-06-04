package com.changyow.mediadler

import android.app.Application
import android.content.Context
import com.changyow.mediadler.data.ytdlp.EngineState
import com.changyow.mediadler.di.AppContainer
import com.changyow.mediadler.download.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MediaDlerApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Warm up yt-dlp/ffmpeg off the main thread. Only refresh yt-dlp on startup
        // when the user has opted in — auto-update is off by default so launches stay
        // fast and offline-friendly; users can update on demand from Settings.
        appScope.launch {
            if (container.engine.ensureInit() is EngineState.Ready) {
                val autoUpdate = container.settingsRepository.settings.first().autoUpdateYtDlpOnStartup
                if (autoUpdate) container.engine.update()
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MediaDlerApp).container
