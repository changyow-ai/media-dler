package com.changyow.mediadler.core.transcribe

/**
 * Opaque handle to an audio source an engine will read. [uri] is a `content://` URI or a file
 * path — :core stays platform-neutral, so it never touches the bytes; the :app engine decodes it.
 */
data class AudioRef(
    val uri: String,
    val durationMs: Long?,
)

/** A finished transcription. */
data class Transcript(
    val text: String,
    /** BCP-47-ish code the engine detected (e.g. "zh", "en"), or null if unknown. */
    val detectedLanguage: String?,
)
