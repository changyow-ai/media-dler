package com.changyow.mediadler

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.changyow.mediadler.core.extract.UrlExtractor
import com.changyow.mediadler.download.DownloadService
import com.changyow.mediadler.ui.picker.ShareSheet
import com.changyow.mediadler.ui.theme.MediaDlerTheme

/** Transparent entry point for ACTION_SEND / ACTION_VIEW / ACTION_PROCESS_TEXT share intents. */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent)
        if (url == null) {
            Toast.makeText(this, "找不到可下載的連結", Toast.LENGTH_SHORT).show()
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
