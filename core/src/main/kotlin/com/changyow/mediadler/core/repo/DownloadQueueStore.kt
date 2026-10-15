package com.changyow.mediadler.core.repo

import com.changyow.mediadler.core.model.QueuedDownload

/** Transaction commits before returning. Failed writes must not replace the previous snapshot. */
interface DownloadQueueStore {
    fun read(): List<QueuedDownload>
    fun transact(change: (List<QueuedDownload>) -> List<QueuedDownload>): List<QueuedDownload>
}
