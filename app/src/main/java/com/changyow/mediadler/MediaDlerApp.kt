package com.changyow.mediadler

import android.app.Application
import android.content.Context
import com.changyow.mediadler.data.ytdlp.EngineState
import com.changyow.mediadler.di.AppContainer
import com.changyow.mediadler.download.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaDlerApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Warm up yt-dlp/ffmpeg off the main thread, then refresh yt-dlp so the
        // bundled (and quickly-stale) copy doesn't break downloads like YouTube.
        appScope.launch {
            if (container.engine.ensureInit() is EngineState.Ready) {
                container.engine.update()
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MediaDlerApp).container
