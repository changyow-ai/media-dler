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

    /** A skipped task must never recover an artifact produced by its earlier ad-analysis attempt. */
    suspend fun recover(
        processId: String,
        skipAdAnalysis: Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<DownloadOutput?> = if (skipAdAnalysis) Result.success(null) else recover(processId, onProgress)

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
