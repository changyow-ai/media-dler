package com.changyow.mediadler.transcribe

import android.content.Context
import android.net.Uri
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.core.transcribe.AudioRef
import com.changyow.mediadler.core.transcribe.AudioWindow
import com.changyow.mediadler.core.transcribe.SegmentMerge
import com.changyow.mediadler.core.transcribe.Transcript
import com.changyow.mediadler.core.transcribe.TranscriptionEngine
import com.changyow.mediadler.core.transcribe.WindowPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * On-device [TranscriptionEngine]/[StreamingEngine] backed by whisper.cpp (JNI). Pipeline: ensure
 * model → decode to 16 kHz mono PCM → window the PCM (~60 s windows = resume checkpoints) → run
 * whisper per window with live segment/progress callbacks → merge → normalise Chinese to traditional.
 *
 * The streaming entry point feeds partial text and progress out as whisper decodes, persists a
 * checkpoint after each window (so an interrupted job resumes from [startWindow] with [priorText]),
 * and honours a cancel flag that aborts mid-window. The whisper model is read from [settings] per run
 * so a model change in settings takes effect on the next job without rebuilding the engine.
 */
class WhisperCppEngine(
    private val context: Context,
    private val models: WhisperModelManager,
    private val settings: SettingsRepository,
    private val fallbackModel: WhisperModel = WhisperModel.BASE,
) : TranscriptionEngine, StreamingEngine {

    override val id = "whisper-cpp"

    override suspend fun transcribe(audio: AudioRef, onProgress: (Float) -> Unit): Transcript =
        transcribeStreaming(audio.uri, onProgress = onProgress).transcript

    override suspend fun transcribeStreaming(
        audioUri: String,
        startWindow: Int,
        priorText: String,
        knownLanguage: String?,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onCheckpoint: (Int, Int, String, String?) -> Unit,
    ): StreamResult = withContext(Dispatchers.Default) {
        val model = runCatching { settings.settings.first().transcribeModel }
            .getOrNull()?.let(WhisperModel::of) ?: fallbackModel
        val modelFile = models.ensure(model) { onProgress(it * 0.05f) }
        val uri = Uri.parse(audioUri)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

        // Plan windows from container duration so PCM is decoded one window at a time (bounded
        // memory). If the duration is unknown, fall back to one streaming pass over the whole file.
        val durationMs = AudioToPcm.durationMs(context, uri)
        val windows = if (durationMs != null) {
            WindowPlanner.plan(durationMs, WINDOW_MS, OVERLAP_MS)
        } else {
            listOf(AudioWindow(0L, Long.MAX_VALUE))
        }
        val total = windows.size

        val ctx = WhisperNative.nativeInit(modelFile.absolutePath)
        require(ctx != 0L) { "whisper model load failed: ${modelFile.name}" }
        try {
            val parts = ArrayList<String>()
            if (priorText.isNotBlank()) parts.add(priorText)
            var detected: String? = knownLanguage
            var lastCompleted = startWindow

            for (index in startWindow until total) {
                if (isCancelled()) {
                    return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                }
                val window = windows[index]
                // Decode just this window's PCM, then drop it before the next window is decoded.
                val slice = AudioToPcm.decodeRange(context, uri, window.startMs, window.endMs)
                if (slice.isEmpty()) {
                    lastCompleted = index + 1
                    onCheckpoint(lastCompleted, total, render(parts, "", detected), detected)
                    continue
                }

                val live = StringBuilder()
                val callback = object : WhisperNative.WhisperCallback {
                    override fun onProgress(percent: Int) {
                        onProgress(0.05f + 0.93f * ((index + percent / 100f) / total))
                    }
                    override fun onSegment(text: String) {
                        live.append(text)
                        onPartial(render(parts, live.toString(), detected))
                    }
                    override fun isCancelled(): Boolean = isCancelled()
                }

                val text = WhisperNative.nativeFullTranscribe(ctx, slice, detected ?: "auto", threads, callback)
                if (isCancelled()) {
                    return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                }
                if (detected == null) {
                    detected = WhisperNative.nativeDetectedLanguage(ctx).ifBlank { null }
                }
                parts.add(text)
                lastCompleted = index + 1
                val merged = render(parts, "", detected)
                onPartial(merged)
                onCheckpoint(lastCompleted, total, merged, detected)
            }
            onProgress(1f)
            result(parts, detected, total, total, cancelled = false)
        } finally {
            WhisperNative.nativeFree(ctx)
        }
    }

    private fun result(
        parts: List<String>,
        language: String?,
        completed: Int,
        total: Int,
        cancelled: Boolean,
    ): StreamResult = StreamResult(
        transcript = Transcript(text = render(parts, "", language).trim(), detectedLanguage = language),
        completedWindows = completed,
        totalWindows = total,
        cancelled = cancelled,
    )

    /** Merges completed window parts with the in-flight buffer, then normalises Chinese to 正體. */
    private fun render(parts: List<String>, live: String, language: String?): String {
        val merged = SegmentMerge.merge(parts)
        val full = if (live.isBlank()) merged else if (merged.isBlank()) live else "$merged$live"
        return OpenCcConverter.normalize(full, language)
    }

    private companion object {
        const val WINDOW_MS = 60_000L  // checkpoint/resume granularity
        const val OVERLAP_MS = 3_000L  // de-duplicated by SegmentMerge
    }
}
