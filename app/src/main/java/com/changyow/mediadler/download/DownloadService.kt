package com.changyow.mediadler.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.QueuePause
import com.changyow.mediadler.core.model.QueuePauseKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

/** Android foreground lifetime; the application runner retains the native attempt until it exits. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = UUID.randomUUID().toString()
    private lateinit var runner: DownloadRunner
    private var latestStartId = 0
    private var stoppedNormally = false
    private var hasOwnedRunner = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: DownloadWifiLock? = null

    override fun onCreate() {
        super.onCreate()
        runner = appContainer.downloadRunner
        ServiceCompat.startForeground(this, Notifications.SUMMARY_ID,
            Notifications.summary(this, appContainer.downloadQueue.activeCount()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "media-dler:download")
            ?.apply { setReferenceCounted(false) }
        wifiLock = DownloadWifiLock(this)
        scope.launch {
            runner.state.collectLatest { state ->
                if (state.owner != owner) {
                    // A recovery worker may acquire a paused queue before this collector observes
                    // PAUSED. The previous service must still release its foreground lifetime.
                    if (hasOwnedRunner) {
                        stoppedNormally = true
                        releaseDownloadLocks()
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return@collectLatest
                }
                hasOwnedRunner = true
                if (state.phase == DownloadRunner.Phase.RUNNING || state.phase == DownloadRunner.Phase.STOPPING) {
                    try {
                        // CPU wakefulness alone does not keep the Wi-Fi radio awake on older phones.
                        wifiLock?.acquire()
                        while (true) {
                            // Bounded leases, renewed only while this foreground service owns work.
                            wakeLock?.acquire(10 * 60_000L)
                            delay(4 * 60_000L)
                        }
                    } finally { releaseDownloadLocks() }
                } else if (state.startId == latestStartId && stopSelfResult(state.startId)) {
                    stoppedNormally = true
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        runner.wake(owner, startId, userInitiated = intent?.getBooleanExtra(EXTRA_USER, false) == true,
            resumeAll = intent?.getBooleanExtra(EXTRA_RESUME, false) == true)
        // A system restart uses the durable queue, without replaying user resume commands.
        // Explicit stopSelf() on idle, pause or timeout still ends the service normally.
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runner.stop(owner, QueuePause(QueuePauseKind.SYSTEM, "系統已暫停背景下載，開啟 App 後可繼續排程"))
        // Android's timeout grace is short: cancellation/ack continue in the application runner.
        stoppedNormally = true
        releaseDownloadLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stoppedNormally) runner.stop(owner)
        scope.cancel()
        releaseDownloadLocks()
        super.onDestroy()
    }

    private fun releaseDownloadLocks() {
        try { wifiLock?.release() }
        finally { wakeLock?.takeIf { it.isHeld }?.release() }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_USER = "user_initiated"
        private const val EXTRA_RESUME = "resume_all"

        /** Called by visible Activity interactions, never a background retry receiver. */
        fun start(context: Context, resumeAll: Boolean = false): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_USER, true).putExtra(EXTRA_RESUME, resumeAll))
            true
        } catch (error: IllegalStateException) {
            startBlocked(context)
            false
        } catch (error: SecurityException) {
            startBlocked(context)
            false
        }

        private fun startBlocked(context: Context) {
            val reason = QueuePause(QueuePauseKind.SYSTEM, "系統暫不允許啟動下載，請開啟 App 後繼續排程")
            context.appContainer.downloadQueue.pause(reason)
            Notifications.downloadPaused(context, reason.message)
        }
    }
}
