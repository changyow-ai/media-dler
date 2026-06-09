package com.changyow.mediadler.transcribe

import android.content.Context
import android.net.Uri
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.core.transcribe.AudioRef
import com.changyow.mediadler.core.transcribe.SegmentMerge
import com.changyow.mediadler.core.transcribe.Transcript
import com.changyow.mediadler.core.transcribe.TranscriptionEngine
import com.changyow.mediadler.core.transcribe.WindowPlanner
import com.changyow.mediadler.util.NetworkStatus
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
        // Don't silently pull a model over mobile data; fail with an actionable message (FAILED keeps
        // the input copy, so it resumes once the model is downloaded on Wi-Fi / via Settings).
        if (!models.isDownloaded(model) && NetworkStatus.isMetered(context)) {
            error("模型「${model.id}」尚未下載，且目前為行動數據。請連 Wi-Fi，或到設定頁先下載模型再轉錄。")
        }
        val modelFile = models.ensure(model) { onProgress(it * 0.05f) }
        val uri = Uri.parse(audioUri)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

        // Plan windows from container duration so PCM is decoded one window at a time (bounded
        // memory). When the duration is unknown we can't pre-plan, so we stream open-ended windows
        // and stop at the first that decodes to nothing — never loading the whole file at once.
        val durationMs = AudioToPcm.durationMs(context, uri)
        val planned = durationMs?.let { WindowPlanner.plan(it, WINDOW_MS, OVERLAP_MS) }
        val total = planned?.size ?: 0 // 0 ⇒ unknown duration (open-ended)

        val ctx = WhisperNative.nativeInit(modelFile.absolutePath)
        require(ctx != 0L) { "whisper model load failed: ${modelFile.name}" }
        try {
            val parts = ArrayList<String>()
            if (priorText.isNotBlank()) parts.add(priorText)
            var detected: String? = knownLanguage
            var lastCompleted = startWindow

            var index = startWindow
            while (planned == null || index < planned.size) {
                if (isCancelled()) {
                    return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                }
                val window = planned?.get(index) ?: WindowPlanner.openWindow(index, WINDOW_MS, OVERLAP_MS)
                // Decode just this window's PCM, then drop it before the next window is decoded.
                // The final (or only) planned window decodes to natural EOF: the floored-to-ms endUs
                // cutoff can race the last frames on short clips.
                val decodeEndMs = if (planned != null && index == planned.lastIndex) Long.MAX_VALUE else window.endMs
                val slice = AudioToPcm.decodeRange(context, uri, window.startMs, decodeEndMs)
                if (slice.isEmpty()) {
                    if (planned == null) break // unknown duration: empty slice = past end-of-stream
                    // Don't advance the resume checkpoint past a window that should have held audio but
                    // decoded empty (likely a decode failure) — persisting it makes a retry skip the
                    // window forever (poisoned-checkpoint bug). Benign tail slivers still advance.
                    val expectedMs = minOf(window.endMs, durationMs ?: window.endMs) - window.startMs
                    if (expectedMs < EMPTY_WINDOW_SKIP_MS) {
                        lastCompleted = index + 1
                        onCheckpoint(lastCompleted, total, render(parts, "", detected), detected)
                    }
                    index++
                    continue
                }

                // Convert the already-committed windows once per window; each live segment then only
                // re-converts its own small delta instead of re-merging + converting the whole text.
                val committed = OpenCcConverter.normalize(SegmentMerge.merge(parts), detected)
                val live = StringBuilder()
                val callback = object : WhisperNative.WhisperCallback {
                    override fun onProgress(percent: Int) {
                        onProgress(0.05f + 0.93f * windowFraction(index, percent / 100f, total))
                    }
                    override fun onSegment(text: String) {
                        live.append(text)
                        onPartial(joinLive(committed, OpenCcConverter.normalize(live.toString(), detected)))
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
                index++
            }
            onProgress(1f)
            result(parts, detected, lastCompleted, maxOf(total, lastCompleted), cancelled = false)
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

    /** Appends the in-flight (already-converted) live text to the committed text for a live preview. */
    private fun joinLive(committed: String, live: String): String =
        if (committed.isBlank()) live else if (live.isBlank()) committed else "$committed$live"

    /**
     * Global progress fraction for window [index] at [withinWindow] (0..1) of its own work. With a
     * known [total] it is linear; with an unknown total (0) it asymptotes toward 1 so the bar still
     * advances without ever falsely reading complete.
     */
    private fun windowFraction(index: Int, withinWindow: Float, total: Int): Float =
        if (total > 0) ((index + withinWindow) / total).coerceIn(0f, 1f)
        else 1f - 1f / (index + 1f + withinWindow)

    private companion object {
        const val WINDOW_MS = 60_000L  // checkpoint/resume granularity
        const val OVERLAP_MS = 3_000L  // de-duplicated by SegmentMerge
        const val EMPTY_WINDOW_SKIP_MS = 3_000L // empties shorter than this are benign tail slivers
    }
}
