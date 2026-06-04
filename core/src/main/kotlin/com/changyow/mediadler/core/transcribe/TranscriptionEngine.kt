package com.changyow.mediadler.core.transcribe

/**
 * Turns an audio source into text. Implementations live in :app (on-device whisper.cpp, cloud
 * OpenRouter); :core owns only the contract so the pipeline and tests stay platform-neutral.
 */
interface TranscriptionEngine {
    /** Stable identifier used in settings/UI, e.g. "whisper-cpp" or "openrouter". */
    val id: String

    /**
     * Transcribes [audio]. [onProgress] reports a best-effort fraction in `0f..1f`.
     * Throws on failure (callers decide whether to fall back to another engine).
     */
    suspend fun transcribe(audio: AudioRef, onProgress: (Float) -> Unit = {}): Transcript
}
