package com.changyow.mediadler.transcribe

import android.content.Context
import android.net.Uri
import com.changyow.mediadler.core.model.OnDeviceBackend
import com.changyow.mediadler.core.model.TranscribeModel
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.core.transcribe.BoundedAudioWindows
import com.changyow.mediadler.core.transcribe.InferenceSlices
import com.changyow.mediadler.core.transcribe.SegmentMerge
import com.changyow.mediadler.core.transcribe.PcmSignal
import com.changyow.mediadler.core.transcribe.Transcript
import com.changyow.mediadler.util.NetworkStatus
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * On-device [StreamingEngine] backed by sherpa-onnx (ONNX Runtime, JNI). Handles the sherpa-backed
 * models, including bounded-context FireRedASR2 AED / Fun-ASR-Nano / Qwen3-ASR.
 * Pipeline mirrors [WhisperCppEngine]: ensure model →
 * window the audio (~60 s windows = resume checkpoints) → decode each window's 16 kHz mono float PCM
 * via OfflineRecognizer → merge overlaps → normalise Chinese to 正體.
 *
 * Native decode has no abort callback. Bounded-context models check progress/cancellation between
 * smaller inference slices; durable checkpoints stay at the original 60-second boundaries.
 * The model is read from
 * [settings] per run, so a model change takes effect on the next job without rebuilding the engine.
 */
class SherpaOnnxEngine(
    private val context: Context,
    private val models: SherpaModelManager,
    private val settings: SettingsRepository,
    private val fallbackModel: TranscribeModel = TranscribeModel.SENSE_VOICE,
) : StreamingEngine {

    // Static id: whisper↔sherpa switching resets the checkpoint (incompatible windows). Switching
    // between two sherpa models mid-job resumes from the checkpoint as-is (same as whisper base↔small).
    override val id = "sherpa-onnx"

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
        val choice = runCatching { settings.settings.first().transcribeModel }
            .getOrNull()?.takeIf { it.backend == OnDeviceBackend.SHERPA } ?: fallbackModel
        val model = SherpaModel.of(choice)
        // Don't silently pull a large model over mobile data; fail with an actionable message so the
        // user downloads it on Wi-Fi or via the (metered-gated) Settings button. FAILED keeps the
        // input copy, so it resumes once the model is present.
        if (!models.isDownloaded(model) && NetworkStatus.isMetered(context)) {
            error("模型「${choice.label.substringBefore("（")}」尚未下載，且目前為行動數據。" +
                "請連 Wi-Fi，或到設定頁先下載模型再轉錄。")
        }
        val modelDir = models.ensure(model) { onProgress(it * 0.05f) }
        val uri = Uri.parse(audioUri)

        // Plan windows from container duration so PCM is decoded one window at a time (bounded memory).
        // Unknown duration ⇒ stream open-ended windows, stopping at the first empty slice.
        val durationMs = AudioToPcm.durationMs(context, uri)
        val windows = BoundedAudioWindows(durationMs, WINDOW_MS, OVERLAP_MS, AudioToPcm.TARGET_SAMPLE_RATE)
        // A failed all-blank run has no transcript worth preserving. Restart it even when the
        // source duration is unknown, otherwise an old checkpoint can keep skipping the only audio.
        val effectiveStart = if (priorText.isBlank()) 0 else startWindow.coerceAtLeast(0)
        var total = windows.totalAt(effectiveStart)

        val recognizer = OfflineRecognizer(
            assetManager = null,
            config = sherpaRecognizerConfig(model, modelDir, knownLanguage),
        )
        try {
            val parts = ArrayList<String>()
            if (priorText.isNotBlank()) parts.add(priorText)
            // Paraformer is Chinese-only, so seed zh up front; SenseVoice fills from result.lang.
            var detected: String? = if (model == SherpaModel.FIRE_RED_ASR2) fireRedOutputLanguage(priorText)
                else knownLanguage ?: defaultLang(model)
            var lastCompleted = effectiveStart

            // Diagnose empty PCM separately from a model finding no speech.
            var maxSliceSamples = 0

            var index = effectiveStart
            while (true) {
                if (isCancelled()) {
                    return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                }
                val window = windows.windowAt(index)
                val stats = AudioToPcm.DecodeStats()
                // A full estimated tail is followed by another bounded window, not treated as EOF.
                val slice = AudioToPcm.decodeRange(context, uri, window.startMs, window.endMs, stats)
                val decision = windows.afterDecode(index, slice.size)
                check(decision.action != BoundedAudioWindows.Action.MISSING_EXPECTED_AUDIO) {
                    "音訊解碼為空（視窗 $index），未跳過此段，請確認來源音軌後重試。\n${stats.summary()}"
                }
                // Do not infer or checkpoint a duplicate overlap-only tail / confirmed out-of-range EOF.
                if (decision.action == BoundedAudioWindows.Action.END_OF_INPUT) break
                total = decision.totalWindows
                maxSliceSamples = maxOf(maxSliceSamples, slice.size)
                if (PcmSignal.isDigitalSilence(slice)) {
                    // SenseVoice/Whisper may hallucinate text on all-zero PCM. Do not use an
                    // energy threshold here: arbitrarily quiet real speech must still be decoded.
                    lastCompleted = index + 1
                    onCheckpoint(lastCompleted, total, mergeSherpaTranscript(parts, detected), detected)
                    if (!decision.hasNext) break
                    index++
                    continue
                }

                // Preserve checkpoint coordinates. SenseVoice/Paraformer still decode whole windows;
                // the LLM exports cannot fit 60 seconds plus output in their fixed 512-token cache.
                val ranges = InferenceSlices.plan(slice.size,
                    model.inferenceSeconds * AudioToPcm.TARGET_SAMPLE_RATE,
                    2 * AudioToPcm.TARGET_SAMPLE_RATE)
                val windowParts = ArrayList<String>()
                for ((partIndex, range) in ranges.withIndex()) {
                    if (isCancelled()) {
                        return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                    }
                    onProgress(0.05f + 0.93f * windowFraction(index,
                        partIndex.toFloat() / ranges.size, total))
                    val samples = if (ranges.size == 1) slice
                        else slice.copyOfRange(range.start, range.endExclusive)
                    if (PcmSignal.isDigitalSilence(samples)) continue
                    val stream = recognizer.createStream()
                    try {
                        if (model == SherpaModel.QWEN3) {
                            qwenLanguageHint(knownLanguage)?.let { stream.setOption("language", it) }
                        }
                        stream.acceptWaveform(samples, AudioToPcm.TARGET_SAMPLE_RATE)
                        recognizer.decode(stream)
                        val r = recognizer.getResult(stream)
                        if (detected == null) detected = normalizeLang(r.lang)
                        // AED has no LID output. Its bilingual Han output identifies Chinese script
                        // for OpenCC, without labelling every English-only transcript as Chinese.
                        if (detected == null && model == SherpaModel.FIRE_RED_ASR2) {
                            detected = fireRedOutputLanguage(r.text)
                        }
                        windowParts.add(r.text)
                    } finally {
                        stream.release()
                    }
                    if (isCancelled()) {
                        return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                    }
                    // Do not publish uncheckpointed slice text: TranscriptionManager.fail() persists
                    // the last live text with completedWindows. Publishing here would make retry
                    // append this window twice after a later inner slice fails.
                }
                // A failed/cancelled inner slice never advances the durable checkpoint.
                parts.add(mergeSherpaTranscript(windowParts, detected))
                lastCompleted = index + 1
                val merged = mergeSherpaTranscript(parts, detected)
                onPartial(merged)
                onProgress(0.05f + 0.93f * windowFraction(index, 1f, total))
                onCheckpoint(lastCompleted, total, merged, detected)
                if (!decision.hasNext) break
                index++
            }
            onProgress(1f)
            check(mergeSherpaTranscript(parts, detected).isNotBlank()) {
                "音訊已解碼（最長視窗 $maxSliceSamples samples），但未辨識到語音。" +
                    "請確認影片有人聲，或選擇正確語言／其他模型後重試。"
            }
            result(parts, detected, lastCompleted, maxOf(total, lastCompleted), cancelled = false)
        } finally {
            recognizer.release()
        }
    }

    /** Default language when none is forced: Paraformer is Chinese-only; others detect per result. */
    private fun defaultLang(model: SherpaModel): String? =
        if (model == SherpaModel.PARAFORMER) "zh" else null

    /** Maps SenseVoice's `result.lang` to a code OpenCC understands (Chinese/Cantonese ⇒ zh). */
    private fun normalizeLang(raw: String): String? {
        val l = raw.trim().lowercase().trim('<', '>', '|')
        if (l.isBlank()) return null
        return when {
            l.startsWith("zh") || l.startsWith("yue") ||
                l.contains("chinese") || l.contains("cantonese") -> "zh"
            else -> l.take(2)
        }
    }

    private fun result(
        parts: List<String>,
        language: String?,
        completed: Int,
        total: Int,
        cancelled: Boolean,
    ): StreamResult = StreamResult(
        transcript = Transcript(text = mergeSherpaTranscript(parts, language).trim(), detectedLanguage = language),
        completedWindows = completed,
        totalWindows = total,
        cancelled = cancelled,
    )

    /** Global progress fraction for window [index] at [withinWindow] of its own work. */
    private fun windowFraction(index: Int, withinWindow: Float, total: Int): Float =
        if (total > 0) ((index + withinWindow) / total).coerceIn(0f, 1f)
        else 1f - 1f / (index + 1f + withinWindow)

    private companion object {
        const val WINDOW_MS = 60_000L  // checkpoint/resume granularity
        const val OVERLAP_MS = 3_000L  // de-duplicated by SegmentMerge
    }
}

/** Match inner-slice and checkpoint seams in the same script before removing their overlap. */
internal fun mergeSherpaTranscript(parts: List<String>, language: String?): String =
    SegmentMerge.merge(parts.map { OpenCcConverter.normalize(it, language) })
