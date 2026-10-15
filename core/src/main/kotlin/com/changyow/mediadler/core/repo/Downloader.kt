package com.changyow.mediadler.core.repo

import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.DownloadProgress

/** Executes the item's page/direct target with its required headers, preserving source identity. */
interface Downloader {
    /** Publish an already completed private artifact before resolving an expiring page again. */
    suspend fun recover(
        processId: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<DownloadOutput?> = Result.success(null)

    suspend fun download(
        request: DownloadRequest,
        processId: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<DownloadOutput>
}

data class DownloadOutput(
    val uri: String,
    val displayName: String,
    val mimeType: String,
)
