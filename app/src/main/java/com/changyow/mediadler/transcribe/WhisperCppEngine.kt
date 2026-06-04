package com.changyow.mediadler.transcribe

import android.content.Context
import android.net.Uri
import com.changyow.mediadler.core.transcribe.AudioRef
import com.changyow.mediadler.core.transcribe.SegmentMerge
import com.changyow.mediadler.core.transcribe.Transcript
import com.changyow.mediadler.core.transcribe.TranscriptionEngine
import com.changyow.mediadler.core.transcribe.WindowPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device [TranscriptionEngine] backed by whisper.cpp (JNI). Pipeline: ensure model → decode to
 * 16 kHz mono PCM → window the PCM → run whisper per window → merge → normalise Chinese to
 * traditional. The first window's detected language is reused for the rest to avoid drift.
 */
class WhisperCppEngine(
    private val context: Context,
    private val models: WhisperModelManager,
    private val model: WhisperModel = WhisperModel.BASE,
) : TranscriptionEngine {

    override val id = "whisper-cpp"

    override suspend fun transcribe(audio: AudioRef, onProgress: (Float) -> Unit): Transcript =
        withContext(Dispatchers.Default) {
            // 0–15%: model download (no-op once cached). 15–95%: transcription. 95–100%: post.
            val modelFile = models.ensure(model) { onProgress(it * 0.15f) }
            val pcm = AudioToPcm.decode(context, Uri.parse(audio.uri))
            val sampleRate = AudioToPcm.TARGET_SAMPLE_RATE
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

            val ctx = WhisperNative.nativeInit(modelFile.absolutePath)
            require(ctx != 0L) { "whisper model load failed: ${modelFile.name}" }
            try {
                val totalMs = pcm.size.toLong() * 1000 / sampleRate
                val windows = WindowPlanner.plan(totalMs)
                val parts = ArrayList<String>(windows.size)
                var detected: String? = null

                windows.forEachIndexed { index, window ->
                    val from = (window.startMs * sampleRate / 1000).toInt().coerceIn(0, pcm.size)
                    val to = (window.endMs * sampleRate / 1000).toInt().coerceIn(from, pcm.size)
                    val slice = if (from == 0 && to == pcm.size) pcm else pcm.copyOfRange(from, to)

                    val text = WhisperNative.nativeFullTranscribe(ctx, slice, detected ?: "auto", threads)
                    if (detected == null) {
                        detected = WhisperNative.nativeDetectedLanguage(ctx).ifBlank { null }
                    }
                    parts.add(text)
                    onProgress(0.15f + 0.80f * ((index + 1f) / windows.size))
                }

                val merged = SegmentMerge.merge(parts)
                val normalized = OpenCcConverter.normalize(merged, detected).trim()
                onProgress(1f)
                Transcript(text = normalized, detectedLanguage = detected)
            } finally {
                WhisperNative.nativeFree(ctx)
            }
        }
}
