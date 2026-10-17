package com.changyow.mediadler.core.model

import kotlinx.serialization.Serializable

enum class DownloadPhase { DOWNLOADING, PROCESSING, SAVING }

/** A phase-local fraction is known only when the source supplied a reliable total. */
data class DownloadProgress(
    val phase: DownloadPhase,
    val fraction: Float = -1f,
    val detail: String? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    /** Report before native work: true if partial files can survive restart; null keeps the last report. */
    val canResume: Boolean? = null,
)

@Serializable enum class QueuePauseKind { SYSTEM, STORAGE, NETWORK }
@Serializable data class QueuePause(val kind: QueuePauseKind, val message: String)
