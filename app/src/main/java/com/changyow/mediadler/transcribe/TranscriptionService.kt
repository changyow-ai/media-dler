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
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.download.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that drains the [TranscriptionManager] queue sequentially. For each job it
 * resolves links (captions shortcut or audio download), runs the streaming whisper engine while
 * pushing live progress/text into the manager + a notification, and persists checkpoints so the job
 * survives the app being backgrounded or killed. Cancellation ("放棄") is observed mid-run.
 */
class TranscriptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val working = AtomicBoolean(false)

    @Volatile
    private var latestStartId = 0

    private val notifyIds = ConcurrentHashMap<String, Int>()
    private val notifyCounter = AtomicInteger(Notifications.TX_SUMMARY_ID + 1)

    private lateinit var container: com.changyow.mediadler.di.AppContainer
    private lateinit var manager: TranscriptionManager
    private lateinit var resolver: LinkAudioResolver
    private lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        container = (application as MediaDlerApp).container
        manager = container.transcriptionManager
        resolver = container.linkAudioResolver
        settings = container.settingsRepository
        ServiceCompat.startForeground(
            this,
            Notifications.TX_SUMMARY_ID,
            Notifications.txSummary(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        ensureWorker()
        return START_NOT_STICKY
    }

    private fun ensureWorker() {
        if (!working.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (isActive) {
                    val job = manager.claimNext() ?: break
                    runCatching { process(job) }
                }
            } finally {
                working.set(false)
                if (manager.hasPending()) {
                    ensureWorker()
                } else {
                    ServiceCompat.stopForeground(this@TranscriptionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(latestStartId)
                }
            }
        }
    }

    private suspend fun process(job: TranscriptJob) {
        val id = job.id
        val notifyId = notifyIds.getOrPut(id) { notifyCounter.getAndIncrement() }
        val open = TranscribeActivity.contentIntent(this, id)
        Notifications.txProgress(this, notifyId, job.label, -1f, open)

        var audioToDelete: java.io.File? = null
        var inputCopy: java.io.File? = null
        try {
            // Resolve a link to captions (instant) or a downloaded audio file before transcribing.
            val audioUri: String = if (job.isUrl) {
                when (val r = resolver.resolve(job.sourceUri) { p -> manager.setProgress(id, p * 0.3f) }) {
                    is LinkAudioResolver.Resolved.Captions -> {
                        if (manager.isCancelRequested(id)) return finishCancelled(notifyId)
                        val text = OpenCcConverter.normalize(r.text, r.language).trim()
                        manager.complete(id, text, r.language)
                        Notifications.txCompleted(this, notifyId, job.label, open)
                        return
                    }
                    is LinkAudioResolver.Resolved.Audio -> {
                        audioToDelete = r.file
                        Uri.fromFile(r.file).toString()
                    }
                }
            } else if (job.sourceUri.startsWith("content://")) {
                // Copy the shared file into private storage: the content-URI grant doesn't outlive
                // the share activity, and a private copy lets an interrupted job resume after death.
                val copy = ensurePrivateCopy(id, job.sourceUri)
                inputCopy = copy
                manager.updateSource(id, Uri.fromFile(copy).toString())
                Uri.fromFile(copy).toString()
            } else {
                inputCopy = runCatching { Uri.parse(job.sourceUri).path?.let { java.io.File(it) } }.getOrNull()
                job.sourceUri
            }

            if (manager.isCancelRequested(id)) return finishCancelled(notifyId)

            // A forced language (settings) wins over auto-detect; else keep the resumed/detected one.
            val snapshot = settings.settings.first()
            val forced = snapshot.transcribeLanguage.code
            val engine = container.streamingEngine(snapshot.transcribeEngine)
            // Bind to the engine; a switch since the last run discards an incompatible checkpoint.
            val current = manager.beginRun(id, engine.id) ?: manager.job(id) ?: job
            val result = engine.transcribeStreaming(
                audioUri = audioUri,
                startWindow = current.completedWindows,
                priorText = current.text,
                knownLanguage = forced ?: current.language,
                isCancelled = { manager.isCancelRequested(id) },
                onProgress = { p ->
                    manager.setProgress(id, p)
                    Notifications.txProgress(this, notifyId, job.label, p, open)
                },
                onPartial = { text -> manager.setLiveText(id, text) },
                onCheckpoint = { done, total, text, lang -> manager.checkpoint(id, done, total, text, lang) },
            )

            when {
                result.cancelled || manager.isCancelRequested(id) -> finishCancelled(notifyId)
                else -> {
                    manager.complete(id, result.transcript.text, result.transcript.detectedLanguage)
                    Notifications.txCompleted(this, notifyId, job.label, open)
                }
            }
        } catch (t: Throwable) {
            if (manager.isCancelRequested(id)) {
                finishCancelled(notifyId)
            } else {
                manager.fail(id, t.message ?: "轉文字失敗")
                Notifications.txFailed(this, notifyId, job.label, t.message)
            }
        } finally {
            // On normal terminal/cancel, drop scratch files. On process death this is skipped, so
            // the private copy survives and the job resumes from its checkpoint next launch.
            // The downloaded audio sits in its own scratch dir — remove the dir, not just the file.
            (audioToDelete?.parentFile ?: audioToDelete)?.deleteRecursively()
            inputCopy?.delete()
        }
    }

    /** Copies a shared content:// file into private cache (idempotent), returning the local file. */
    private fun ensurePrivateCopy(id: String, contentUri: String): java.io.File {
        val dir = java.io.File(cacheDir, "transcribe/input").apply { mkdirs() }
        val dest = java.io.File(dir, id)
        if (dest.exists() && dest.length() > 0) return dest
        contentResolver.openInputStream(Uri.parse(contentUri)).use { input ->
            requireNotNull(input) { "無法讀取分享的檔案" }
            dest.outputStream().use { input.copyTo(it) }
        }
        return dest
    }

    private fun finishCancelled(notifyId: Int) {
        Notifications.cancel(this, notifyId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /**
         * Starts the service. [grantUri] (a shared content:// file) is forwarded with a read grant so
         * the service can copy it into private storage before the share's grant expires.
         */
        fun start(context: Context, grantUri: Uri? = null) {
            runCatching {
                val intent = Intent(context, TranscriptionService::class.java)
                if (grantUri != null) {
                    intent.data = grantUri
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
