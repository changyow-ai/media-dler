package com.changyow.mediadler

import android.app.Application
import android.content.Context
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
        // Warm up yt-dlp/ffmpeg off the main thread so the first share is fast.
        appScope.launch { container.engine.ensureInit() }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MediaDlerApp).container
