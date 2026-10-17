package com.changyow.mediadler.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.QueuePause
import com.changyow.mediadler.core.model.QueuePauseKind
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Runs through the same actor as visible downloads; never starts a service from the background. */
class DownloadRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val queue = container.downloadQueue
        val runner = container.downloadRunner
        if (!hasRecoverableDownloads(queue.tasks.value)) return Result.success()
        if (!container.downloadNetwork.available.value || runner.state.value.isRunning) return Result.retry()

        // Android 12+ can reject foreground promotion even for a scheduled worker.
        // Use ordinary job windows there; older Android can retain a long-running foreground worker.
        val windowMillis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) WORK_WINDOW_MILLIS else null
        if (windowMillis == null) {
            try {
                setForeground(ForegroundInfo(FOREGROUND_ID, Notifications.summary(applicationContext, queue.activeCount()),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                return foregroundBlocked(error)
            } catch (error: SecurityException) {
                return foregroundBlocked(error)
            }
        }

        // WorkManager reuses its work ID across retries; each execution needs a fresh owner.
        val owner = "worker-${UUID.randomUUID()}"
        val wifiLock = DownloadWifiLock(applicationContext)
        wifiLock.acquire()
        try {
            runner.runBackgroundWindow(owner, windowMillis)
        } finally {
            // The runner acknowledges native exit before the window returns or propagates cancellation.
            wifiLock.release()
            if (runner.state.value.owner == owner) Notifications.cancel(applicationContext, Notifications.SUMMARY_ID)
        }
        return recoveryResult()
    }

    private fun recoveryResult(): Result =
        if (hasRecoverableDownloads(applicationContext.appContainer.downloadQueue.tasks.value)) Result.retry()
        else Result.success()

    private fun foregroundBlocked(error: Exception): Result {
        Log.w("DownloadRecoveryWorker", "Unable to promote recovery worker", error)
        val container = applicationContext.appContainer
        if (container.downloadRunner.state.value.isRunning) return Result.retry()
        val reason = QueuePause(QueuePauseKind.SYSTEM, "系統暫不允許背景恢復下載，請開啟 App 後繼續排程")
        container.downloadQueue.pause(reason)
        Notifications.downloadPaused(applicationContext, reason.message)
        return Result.success()
    }

    private val DownloadRunner.State.isRunning: Boolean
        get() = phase == DownloadRunner.Phase.RUNNING || phase == DownloadRunner.Phase.STOPPING

    companion object {
        // Independent of DownloadService's foreground notification during owner handover.
        const val FOREGROUND_ID = 50_000
        private const val WORK_WINDOW_MILLIS = 8 * 60_000L
    }
}
