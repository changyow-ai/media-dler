package com.changyow.mediadler.transcribe

import android.content.Context
import android.net.Uri
import com.changyow.mediadler.core.model.OnDeviceBackend
import com.changyow.mediadler.core.model.TranscribeModel
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.core.transcribe.BoundedAudioWindows
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
 * models — SenseVoice / Paraformer (non-autoregressive, strong + fast on Chinese) and Qwen3-ASR
 * (autoregressive, high-quality but heavy). Pipeline mirrors [WhisperCppEngine]: ensure model →
 * window the audio (~60 s windows = resume checkpoints) → decode each window's 16 kHz mono float PCM
 * via OfflineRecognizer → merge overlaps → normalise Chinese to 正體.
 *
 * Unlike whisper there is no within-window progress callback (these models decode a whole window in
 * one shot), so progress advances per window — fine because they are fast. The model is read from
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
            var detected: String? = knownLanguage ?: defaultLang(model)
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
                    onCheckpoint(lastCompleted, total, render(parts, detected), detected)
                    if (!decision.hasNext) break
                    index++
                    continue
                }

                // One-shot decode; reflect progress at window boundaries (no within-window callback).
                onProgress(0.05f + 0.93f * windowFraction(index, 0f, total))
                val stream = recognizer.createStream()
                val text: String
                val lang: String
                try {
                    stream.acceptWaveform(slice, AudioToPcm.TARGET_SAMPLE_RATE)
                    recognizer.decode(stream)
                    val r = recognizer.getResult(stream)
                    text = r.text
                    lang = r.lang
                } finally {
                    stream.release()
                }
                if (isCancelled()) {
                    return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                }
                if (detected == null) detected = normalizeLang(lang)
                parts.add(text)
                lastCompleted = index + 1
                val merged = render(parts, detected)
                onPartial(merged)
                onProgress(0.05f + 0.93f * windowFraction(index, 1f, total))
                onCheckpoint(lastCompleted, total, merged, detected)
                if (!decision.hasNext) break
                index++
            }
            onProgress(1f)
            check(render(parts, detected).isNotBlank()) {
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
        transcript = Transcript(text = render(parts, language).trim(), detectedLanguage = language),
        completedWindows = completed,
        totalWindows = total,
        cancelled = cancelled,
    )

    /** Merges completed window parts (de-duplicating overlaps), then normalises Chinese to 正體. */
    private fun render(parts: List<String>, language: String?): String =
        OpenCcConverter.normalize(SegmentMerge.merge(parts), language)

    /** Global progress fraction for window [index] at [withinWindow] of its own work. */
    private fun windowFraction(index: Int, withinWindow: Float, total: Int): Float =
        if (total > 0) ((index + withinWindow) / total).coerceIn(0f, 1f)
        else 1f - 1f / (index + 1f + withinWindow)

    private companion object {
        const val WINDOW_MS = 60_000L  // checkpoint/resume granularity
        const val OVERLAP_MS = 3_000L  // de-duplicated by SegmentMerge
    }
}
