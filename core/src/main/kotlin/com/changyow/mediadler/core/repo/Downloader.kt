package com.changyow.mediadler.core.repo

import com.changyow.mediadler.core.model.DownloadRequest

/** Executes a download and reports the resulting saved file. */
interface Downloader {
    suspend fun download(
        request: DownloadRequest,
        processId: String,
        onProgress: (progress: Float, line: String) -> Unit,
    ): Result<DownloadOutput>
}

data class DownloadOutput(
    val uri: String,
    val displayName: String,
    val mimeType: String,
)
