package com.changyow.mediadler

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.changyow.mediadler.ui.theme.MediaDlerTheme
import com.changyow.mediadler.ui.transcribe.TranscribeScreen
import com.changyow.mediadler.ui.transcribe.TranscribeViewModel

/**
 * Full-screen transcribe flow. Launched from [ShareReceiverActivity] (local file or link), from a
 * completion notification, or auto-opened for an unseen finished job. It observes the shared job in
 * [com.changyow.mediadler.transcribe.TranscriptionManager] — the work itself runs in the service.
 */
class TranscribeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val jobId = intent.getStringExtra(EXTRA_JOB_ID)
        if (jobId == null) {
            finish()
            return
        }
        val manager = appContainer.transcriptionManager

        val viewModel: TranscribeViewModel by viewModels {
            viewModelFactory {
                initializer { TranscribeViewModel(manager, jobId) }
            }
        }

        setContent {
            MediaDlerTheme {
                val job by viewModel.job.collectAsStateWithLifecycle()
                TranscribeScreen(
                    job = job,
                    onCancel = {
                        viewModel.cancel()
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_JOB_ID = "extra_job_id"

        fun start(context: Context, jobId: String) {
            context.startActivity(intentFor(context, jobId))
        }

        /** PendingIntent for a notification tap → opens this job's result page. */
        fun contentIntent(context: Context, jobId: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                jobId.hashCode(),
                intentFor(context, jobId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun intentFor(context: Context, jobId: String): Intent =
            Intent(context, TranscribeActivity::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        /** Best-effort human-readable name for a content/file [uri] (used as the job label). */
        fun displayName(context: Context, uri: Uri): String {
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
