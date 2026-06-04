package com.changyow.mediadler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.changyow.mediadler.ui.theme.MediaDlerTheme
import com.changyow.mediadler.ui.transcribe.TranscribeScreen

/**
 * Full-screen result page for the transcribe flow, launched from [ShareReceiverActivity] when the
 * user shares a local video/voice file. Milestone 0 only displays the skeleton; the engine that
 * produces the text is wired in milestone 1.
 */
class TranscribeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent.getStringExtra(EXTRA_URI)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: uri.orEmpty()
        setContent {
            MediaDlerTheme {
                TranscribeScreen(
                    sourceLabel = label,
                    transcript = "",
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_LABEL = "extra_label"

        /** Launches the result page for [audioUri], granting it read access. */
        fun start(context: Context, audioUri: Uri) {
            val intent = Intent(context, TranscribeActivity::class.java).apply {
                putExtra(EXTRA_URI, audioUri.toString())
                putExtra(EXTRA_LABEL, displayName(context, audioUri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                data = audioUri
            }
            context.startActivity(intent)
        }

        /** Best-effort human-readable name for a content/file [uri]. */
        private fun displayName(context: Context, uri: Uri): String {
            if (uri.scheme == "content") {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) {
                            val name = it.getString(0)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
            return uri.lastPathSegment ?: uri.toString()
        }
    }
}
