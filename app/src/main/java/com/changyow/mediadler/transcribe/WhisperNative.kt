package com.changyow.mediadler.transcribe

/**
 * Thin JNI binding to whisper.cpp (native lib `whisper_jni`). All calls are blocking and must run
 * off the main thread. A context pointer from [nativeInit] must be released with [nativeFree].
 */
object WhisperNative {
    init {
        System.loadLibrary("whisper_jni")
    }

    /** Live updates during a [nativeFullTranscribe] call. Invoked on the calling thread. */
    interface WhisperCallback {
        /** Window-internal progress, 0..100. */
        fun onProgress(percent: Int)

        /** One freshly decoded segment of text (fires as whisper recognizes each phrase). */
        fun onSegment(text: String)

        /** Polled by whisper; return true to abort the current window (the "放棄" action). */
        fun isCancelled(): Boolean
    }

    /** Loads a ggml model; returns an opaque context pointer, or 0 on failure. */
    external fun nativeInit(modelPath: String): Long

    external fun nativeFree(ptr: Long)

    /**
     * Transcribes 16 kHz mono float PCM in [samples]. [language] is an ISO code or "auto".
     * [callback] (nullable) streams per-segment text and progress. Returns the concatenated
     * segment text (empty on failure).
     */
    external fun nativeFullTranscribe(
        ptr: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        callback: WhisperCallback?,
    ): String

    /** Language whisper detected on the most recent [nativeFullTranscribe] (e.g. "zh"), or "". */
    external fun nativeDetectedLanguage(ptr: Long): String
}
