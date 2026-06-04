package com.changyow.mediadler.transcribe

import kotlinx.serialization.Serializable

enum class TranscriptStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * One transcription job — the single source of truth shared by the foreground service (writer),
 * the result UI, the notification, and the history list (readers). Persisted by [com.changyow
 * .mediadler.data.transcribe.TranscriptStore] so an interrupted job can resume from
 * [completedWindows] and a finished-but-unseen one can be surfaced on next launch.
 */
@Serializable
data class TranscriptJob(
    val id: String,
    /** Source: a `content://` file URI or a web URL (see [isUrl]). */
    val sourceUri: String,
    val isUrl: Boolean,
    val label: String,
    val status: TranscriptStatus,
    val progress: Float = 0f,
    /** Text decoded so far (partial while RUNNING, full when COMPLETED). */
    val text: String = "",
    val language: String? = null,
    /** Checkpoint: windows fully transcribed (resume starts here). */
    val completedWindows: Int = 0,
    val totalWindows: Int = 0,
    /** Engine that produced the checkpoint; a switch invalidates it (different window scheme). */
    val engineId: String? = null,
    val error: String? = null,
    /** Whether the user has already opened the finished result (drives the auto-jump). */
    val seen: Boolean = false,
    val createdAt: Long = 0L,
) {
    val isTerminal: Boolean
        get() = status == TranscriptStatus.COMPLETED ||
            status == TranscriptStatus.FAILED ||
            status == TranscriptStatus.CANCELLED

    companion object {
        /** Stable id from the source, so re-sharing the same input resumes instead of duplicating. */
        fun idFor(sourceUri: String): String = "tx-${sourceUri.hashCode()}"
    }
}
