package com.changyow.mediadler.download

import android.content.Context
import android.net.Uri
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.QueuePause
import com.changyow.mediadler.core.repo.DownloadOutput
import com.changyow.mediadler.data.history.HistoryStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Application-owned notification/history side effects survive service recreation. */
class DownloadObserver(
    private val context: Context,
    private val queue: DownloadQueue,
    private val history: HistoryStore,
    private val previews: PreviewStore,
) : DownloadRunner.Listener {
    private val ids = ConcurrentHashMap<String, Int>()
    private val counter = AtomicInteger(Notifications.SUMMARY_ID + 1)
    private fun notificationId(id: String) = ids.getOrPut(id) { counter.getAndIncrement() }

    override fun onChanged(task: DownloadTask) { Notifications.downloadProgress(context, task) }

    override suspend fun onCompleted(task: DownloadTask, output: DownloadOutput) {
        if (output.mimeType.startsWith("video/")) {
            previews.generate(Uri.parse(output.uri), output.displayName)?.let { queue.setPreview(task.id, it) }
        }
        queue.get(task.id)?.let { history.add(it) }
        Notifications.completed(context, notificationId(task.id), task.title, output.uri, output.mimeType)
    }

    override suspend fun onFailed(task: DownloadTask, error: Throwable) {
        history.add(task)
        Notifications.downloadFailed(context, notificationId(task.id), task)
    }

    override fun onStopped(taskId: String, task: DownloadTask?) {
        Notifications.cancel(context, notificationId(taskId))
    }

    override fun onPaused(reason: QueuePause) { Notifications.downloadPaused(context, reason.message) }
}
