package com.changyow.mediadler.transcribe

import com.changyow.mediadler.core.transcribe.Transcript

/** Result of one streaming run; [completedWindows] < [totalWindows] means it was cancelled. */
data class StreamResult(
    val transcript: Transcript,
    val completedWindows: Int,
    val totalWindows: Int,
    val cancelled: Boolean,
)

/**
 * A transcription engine that streams partial text + progress out and checkpoints per window, so a
 * job can resume after process death (from [startWindow] with [priorText]) and be cancelled mid-run.
 * Both the on-device whisper engine and the cloud engine implement this, letting the foreground
 * service drive either one with identical resume/cancel/notification logic.
 */
interface StreamingEngine {
    /** Stable identifier used in logs/UI, e.g. "whisper-cpp" or "cloud". */
    val id: String

    suspend fun transcribeStreaming(
        audioUri: String,
        startWindow: Int = 0,
        priorText: String = "",
        knownLanguage: String? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
        onPartial: (String) -> Unit = {},
        onCheckpoint: (Int, Int, String, String?) -> Unit = { _, _, _, _ -> },
    ): StreamResult
}
