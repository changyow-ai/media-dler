package com.changyow.mediadler.core.model

import kotlinx.serialization.Serializable

@Serializable enum class DownloadStatus { QUEUED, EXTRACTING, DOWNLOADING, PROCESSING, COMPLETED, FAILED, CANCELED }

val DownloadStatus.isActive: Boolean
    get() = this == DownloadStatus.EXTRACTING || this == DownloadStatus.DOWNLOADING || this == DownloadStatus.PROCESSING

val DownloadStatus.isTerminal: Boolean
    get() = this == DownloadStatus.COMPLETED || this == DownloadStatus.FAILED || this == DownloadStatus.CANCELED

@Serializable data class DownloadTask(
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
    val sourceKind: SourceKind = SourceKind.GENERAL,
    val pageUrl: String = sourceUrl,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val originalTitle: String? = null,
    val queuePosition: Long = 0,
    val progressDetail: String? = null,
    val cancelRequested: Boolean = false,
    val queuePause: QueuePause? = null,
    val lineId: String? = null,
    val lineName: String? = null,
)

/** How the user wants a [MediaItem] downloaded. */
@Serializable sealed interface FormatSelection {
    @Serializable data object BestVideo : FormatSelection
    @Serializable data class CappedVideo(val maxHeight: Int) : FormatSelection
    @Serializable data class SpecificFormat(val format: MediaFormat) : FormatSelection
    @Serializable data class Audio(val audioFormat: AudioFormat) : FormatSelection
    @Serializable data object ImageOriginal : FormatSelection
}

/** A concrete unit of work submitted to the download queue. */
@Serializable data class DownloadRequest(
    val item: MediaItem,
    val selection: FormatSelection,
)
