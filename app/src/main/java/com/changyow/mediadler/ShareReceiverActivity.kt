package com.changyow.mediadler

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.changyow.mediadler.core.extract.UrlExtractor
import com.changyow.mediadler.download.DownloadService
import com.changyow.mediadler.transcribe.TranscriptJob
import com.changyow.mediadler.transcribe.TranscriptionService
import com.changyow.mediadler.ui.picker.ShareSheet
import com.changyow.mediadler.ui.theme.MediaDlerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Transparent entry point for ACTION_SEND / ACTION_VIEW / ACTION_PROCESS_TEXT share intents. */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A shared local video/voice file goes straight to the transcribe pipeline.
        val mediaStream = extractMediaStream(intent)
        if (mediaStream != null) {
            transcribeLocalFile(mediaStream)
            return
        }

        val url = extractUrl(intent)
        if (url == null) {
            Toast.makeText(this, "找不到可下載或轉錄的內容", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            MediaDlerTheme {
                ShareSheet(
                    url = url,
                    onSubmitted = { DownloadService.start(this) },
                    onTranscribe = {
                        startTranscribe(TranscriptJob.idFor(url), url, isUrl = true, label = url)
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    /**
     * Copies the shared file into private storage (we hold the read grant here; the background
     * service won't), then enqueues it. Done off the main thread for large videos.
     */
    private fun transcribeLocalFile(uri: Uri) {
        val label = TranscribeActivity.displayName(this, uri)
        val id = TranscriptJob.idFor(uri.toString())
        lifecycleScope.launch {
            val copied = runCatching { copyToPrivate(uri, id) }.getOrNull()
            if (copied == null) {
                Toast.makeText(this@ShareReceiverActivity, "無法讀取分享的檔案", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            startTranscribe(id, Uri.fromFile(copied).toString(), isUrl = false, label = label)
            finish()
        }
    }

    private suspend fun copyToPrivate(uri: Uri, id: String): File = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "transcribe/input").apply { mkdirs() }
        val dest = File(dir, id)
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "no input stream" }
            dest.outputStream().use { input.copyTo(it) }
        }
        dest
    }

    /** Enqueues a transcription job, starts the foreground service, and opens its result page. */
    private fun startTranscribe(id: String, sourceUri: String, isUrl: Boolean, label: String) {
        appContainer.transcriptionManager.enqueue(
            id = id,
            sourceUri = sourceUri,
            isUrl = isUrl,
            label = label,
            now = System.currentTimeMillis(),
        )
        TranscriptionService.start(this)
        TranscribeActivity.start(this, id)
    }

    /** A shared local video/voice file, if this is a media ACTION_SEND intent. */
    private fun extractMediaStream(intent: Intent?): Uri? {
        intent ?: return null
        if (intent.action != Intent.ACTION_SEND) return null
        val type = intent.type ?: return null
        if (!type.startsWith("video/") && !type.startsWith("audio/")) return null
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }

    private fun extractUrl(intent: Intent?): String? {
        intent ?: return null
        val candidates = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
            intent.dataString,
        )
        for (candidate in candidates) {
            UrlExtractor.firstUrl(candidate)?.let { return it }
        }
        return null
    }
}
