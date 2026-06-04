package com.changyow.mediadler.transcribe

/**
 * Thin JNI binding to whisper.cpp (native lib `whisper_jni`). All calls are blocking and must run
 * off the main thread. A context pointer from [nativeInit] must be released with [nativeFree].
 */
object WhisperNative {
    init {
        System.loadLibrary("whisper_jni")
    }

    /** Loads a ggml model; returns an opaque context pointer, or 0 on failure. */
    external fun nativeInit(modelPath: String): Long

    external fun nativeFree(ptr: Long)

    /**
     * Transcribes 16 kHz mono float PCM in [samples]. [language] is an ISO code or "auto".
     * Returns the concatenated segment text (empty on failure).
     */
    external fun nativeFullTranscribe(ptr: Long, samples: FloatArray, language: String, threads: Int): String

    /** Language whisper detected on the most recent [nativeFullTranscribe] (e.g. "zh"), or "". */
    external fun nativeDetectedLanguage(ptr: Long): String
}
