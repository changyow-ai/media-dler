package com.changyow.mediadler.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.changyow.mediadler.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Cancellation is durable intent only; the runner acknowledges after the native writers exit. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val taskId = intent.getStringExtra(EXTRA_TASK) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { context.appContainer.downloadQueue.cancel(taskId) } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.changyow.mediadler.CANCEL_DOWNLOAD"
        const val EXTRA_TASK = "download_task_id"
    }
}
