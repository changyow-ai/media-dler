package com.changyow.mediadler

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import com.changyow.mediadler.core.extract.UrlExtractor
import com.changyow.mediadler.download.DownloadService
import com.changyow.mediadler.ui.picker.ShareSheet
import com.changyow.mediadler.ui.theme.MediaDlerTheme

/** Transparent entry point for ACTION_SEND / ACTION_VIEW / ACTION_PROCESS_TEXT share intents. */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A shared local video/voice file goes straight to the transcribe result page.
        val mediaStream = extractMediaStream(intent)
        if (mediaStream != null) {
            TranscribeActivity.start(this, mediaStream)
            finish()
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
                    onClose = { finish() },
                )
            }
        }
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
