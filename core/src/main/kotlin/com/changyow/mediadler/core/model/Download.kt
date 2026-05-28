package com.changyow.mediadler.core.model

enum class DownloadStatus { QUEUED, EXTRACTING, DOWNLOADING, PROCESSING, COMPLETED, FAILED, CANCELED }

val DownloadStatus.isTerminal: Boolean
    get() = this == DownloadStatus.COMPLETED || this == DownloadStatus.FAILED || this == DownloadStatus.CANCELED

data class DownloadTask(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String?,
    val formatLabel: String,
    val status: DownloadStatus,
    val progress: Float,
    val outputUri: String?,
    val mimeType: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val previewPath: String? = null,
)

/** How the user wants a [MediaItem] downloaded. */
sealed interface FormatSelection {
    data object BestVideo : FormatSelection
    data class CappedVideo(val maxHeight: Int) : FormatSelection
    data class SpecificFormat(val format: MediaFormat) : FormatSelection
    data class Audio(val audioFormat: AudioFormat) : FormatSelection
    data object ImageOriginal : FormatSelection
}

/** A concrete unit of work submitted to the download queue. */
data class DownloadRequest(
    val item: MediaItem,
    val selection: FormatSelection,
)
