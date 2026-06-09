package com.changyow.mediadler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
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

    // Foreground = at least one Activity is started. Read at job-completion time to decide whether a
    // finished transcript auto-opens on next launch: a job that finishes while the user is still in
    // the app is treated as already seen (they were watching it stream); one that finishes after they
    // left the app stays unseen so it surfaces next launch. @Volatile: written on the main thread by
    // the lifecycle callbacks, read off a background thread by TranscriptionManager.complete().
    @Volatile
    var isForeground = false
        private set
    private var startedActivities = 0

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this) { isForeground }
        registerActivityLifecycleCallbacks(ForegroundTracker())
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

    /** Tracks app foreground state via started-Activity count (no extra lifecycle dependency). */
    private inner class ForegroundTracker : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            isForeground = true
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
            if (startedActivities <= 0) {
                startedActivities = 0
                isForeground = false
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MediaDlerApp).container
