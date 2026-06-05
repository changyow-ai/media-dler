package com.changyow.mediadler.transcribe

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.changyow.mediadler.MediaDlerApp
import com.changyow.mediadler.TranscribeActivity
import com.changyow.mediadler.core.model.StorageMode
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.di.AppContainer
import com.changyow.mediadler.download.Notifications
import com.changyow.mediadler.util.FileNames
import com.changyow.mediadler.util.MimeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that extracts the audio track of a shared video into a standalone `.m4a`
 * (lossless container remux via [AudioTrackRemuxer]) and saves it to the user's storage location —
 * the "取出聲音" share option. Independent of the download queue: it remuxes to a cache file, hands
 * that to the same [com.changyow.mediadler.data.storage.MediaStoreStorage]/SafStorage the downloader
 * uses, then notifies. Each share starts one extraction; the service stops when all finish.
 */
class AudioExtractionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)
    private val notifyCounter = AtomicInteger(EXTRACT_SUMMARY_ID + 1)
    private var foregrounded = false

    private lateinit var container: AppContainer
    private lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        container = (application as MediaDlerApp).container
        settings = container.settingsRepository
        foregrounded = runCatching {
            ServiceCompat.startForeground(
                this,
                EXTRACT_SUMMARY_ID,
                Notifications.extractSummary(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }.isSuccess
        if (!foregrounded) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data
        if (!foregrounded || uri == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        active.incrementAndGet()
        scope.launch {
            try {
                extract(uri)
            } finally {
                if (active.decrementAndGet() == 0) stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun extract(uri: Uri) {
        val notifyId = notifyCounter.getAndIncrement()
        val source = TranscribeActivity.displayName(this, uri)
        val title = source.substringBeforeLast('.', source)
        Notifications.progress(this, notifyId, title, -1f)

        val scratch = File(cacheDir, "extract").apply { mkdirs() }
        val temp = File(scratch, "$notifyId.m4a")
        try {
            AudioTrackRemuxer.remuxToM4a(this, uri, temp)
            val displayName = "${FileNames.sanitize(title)}.m4a"
            val mime = MimeTypes.fromExtension("m4a")
            val snapshot = settings.settings.first()
            val saved = if (snapshot.storageMode == StorageMode.SAF) {
                container.safStorage.save(temp, displayName, mime, snapshot.safTreeUri)
            } else {
                container.mediaStoreStorage.save(temp, displayName, mime)
            }
            Notifications.extracted(this, notifyId, title, saved.toString(), mime)
        } catch (t: Throwable) {
            Notifications.extractFailed(this, notifyId, title, t.message)
        } finally {
            temp.delete()
        }
    }

    private fun stopIfIdle() {
        if (active.get() == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRACT_SUMMARY_ID = 200_000

        /** Starts extraction of [uri]'s audio. The read grant lets the service read the shared file. */
        fun start(context: Context, uri: Uri) {
            runCatching {
                val intent = Intent(context, AudioExtractionService::class.java).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
