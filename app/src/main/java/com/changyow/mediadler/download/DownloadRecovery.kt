package com.changyow.mediadler.download

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.QueuePauseKind
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.isActive
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One durable recovery job protects the entire FIFO, including process death during a download. */
class DownloadRecovery(
    context: Context,
    private val queue: DownloadQueue,
    private val network: DownloadNetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val scheduling = Mutex()

    fun start() {
        scope.launch {
            // Progress updates must not enqueue more work or cancel the currently running worker.
            queue.tasks.map(::hasRecoverableDownloads).distinctUntilChanged().collect { pending ->
                if (pending) schedule()
            }
        }
        scope.launch {
            network.available.collect { online ->
                if (online && hasRecoverableDownloads(queue.tasks.value)) schedule()
            }
        }
        scope.launch {
            // KEEP can race a finishing worker: a newly queued task must get a successor
            // even if its enqueue was ignored while the previous worker was still RUNNING.
            workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).collect { work ->
                if (work.isNotEmpty() && work.all { it.state.isFinished } && hasRecoverableDownloads(queue.tasks.value)) {
                    schedule(delaySeconds = if (work.any { it.state == WorkInfo.State.FAILED }) 30 else 0)
                }
            }
        }
    }

    private suspend fun schedule(delaySeconds: Long = 0) = scheduling.withLock {
        withContext(Dispatchers.IO) {
            try {
                val request = OneTimeWorkRequestBuilder<DownloadRecoveryWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                    .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                    .build()
                workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request).result.get()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("DownloadRecovery", "Unable to schedule download recovery", error)
            }
        }
    }

    companion object {
        const val WORK_NAME = "download-network-recovery"
    }
}

internal fun hasRecoverableDownloads(tasks: List<DownloadTask>): Boolean =
    tasks.any { it.status == DownloadStatus.QUEUED || it.status.isActive } && tasks.none {
        it.queuePause?.kind == QueuePauseKind.STORAGE || it.queuePause?.kind == QueuePauseKind.SYSTEM
    }
