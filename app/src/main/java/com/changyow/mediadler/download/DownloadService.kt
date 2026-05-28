package com.changyow.mediadler.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.changyow.mediadler.MediaDlerApp
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.repo.Downloader
import com.changyow.mediadler.data.history.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground service that drains [DownloadQueue] sequentially with progress notifications. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val working = AtomicBoolean(false)

    private lateinit var queue: DownloadQueue
    private lateinit var downloader: Downloader
    private lateinit var history: HistoryStore

    override fun onCreate() {
        super.onCreate()
        val container = (application as MediaDlerApp).container
        queue = container.downloadQueue
        downloader = container.downloader
        history = container.historyStore
        ServiceCompat.startForeground(
            this,
            Notifications.SUMMARY_ID,
            Notifications.summary(this, queue.activeCount()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureWorker()
        return START_NOT_STICKY
    }

    private fun ensureWorker() {
        if (!working.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (isActive) {
                    val claim = queue.claimNext() ?: break
                    process(claim.first, claim.second)
                }
            } finally {
                working.set(false)
                if (queue.hasQueued()) {
                    ensureWorker()
                } else {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun process(id: String, request: DownloadRequest) {
        val notifyId = id.hashCode()
        val title = queue.get(id)?.title ?: request.item.title
        Notifications.progress(this, notifyId, title, -1f)

        val result = downloader.download(request, id) { progress, _ ->
            queue.setProgress(id, progress)
            Notifications.progress(this, notifyId, title, progress)
        }

        result.fold(
            onSuccess = { output ->
                queue.complete(id, output.uri, output.mimeType)
                queue.get(id)?.let { history.add(it) }
                Notifications.completed(this, notifyId, title, output.uri, output.mimeType)
            },
            onFailure = { error ->
                queue.fail(id, error.message ?: "下載失敗")
                queue.get(id)?.let { history.add(it) }
                Notifications.failed(this, notifyId, title, error.message)
            },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
