package com.changyow.mediadler.transcribe

import android.content.Context
import android.net.Uri
import android.util.Log
import com.changyow.mediadler.core.model.OnDeviceBackend
import com.changyow.mediadler.core.model.TranscribeModel
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.core.transcribe.SegmentMerge
import com.changyow.mediadler.core.transcribe.Transcript
import com.changyow.mediadler.core.transcribe.WindowPlanner
import com.changyow.mediadler.util.NetworkStatus
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

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
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

        // Plan windows from container duration so PCM is decoded one window at a time (bounded memory).
        // Unknown duration ⇒ stream open-ended windows, stopping at the first empty slice.
        val durationMs = AudioToPcm.durationMs(context, uri)
        val planned = durationMs?.let { WindowPlanner.plan(it, WINDOW_MS, OVERLAP_MS) }
        val total = planned?.size ?: 0

        val recognizer = OfflineRecognizer(
            assetManager = null,
            config = configFor(model, modelDir, knownLanguage, threads),
        )
        try {
            val parts = ArrayList<String>()
            if (priorText.isNotBlank()) parts.add(priorText)
            // Paraformer is Chinese-only, so seed zh up front; SenseVoice fills from result.lang.
            var detected: String? = knownLanguage ?: defaultLang(model)
            var lastCompleted = startWindow

            // On-device decode telemetry: when a window that should hold real audio (≥ EMPTY_DIAG_MIN_MS
            // of expected content) decodes to an empty slice, record a one-line dump. Surfaced in the
            // failure message below so the bug is debuggable where logcat isn't (e.g. locked-down S23U).
            val emptyDiag = StringBuilder()
            var maxSliceSamples = 0

            var index = startWindow
            while (planned == null || index < planned.size) {
                if (isCancelled()) {
                    return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                }
                val window = planned?.get(index) ?: WindowPlanner.openWindow(index, WINDOW_MS, OVERLAP_MS)
                val stats = AudioToPcm.DecodeStats()
                // For the final (or only) planned window, decode to natural EOF instead of the exact
                // window end: the floored-to-ms endUs cutoff can race the last frames on short clips.
                val decodeEndMs = if (planned != null && index == planned.lastIndex) Long.MAX_VALUE else window.endMs
                val slice = AudioToPcm.decodeRange(context, uri, window.startMs, decodeEndMs, stats)
                if (slice.isEmpty()) {
                    // A window that should contain audio but decoded empty is the symptom of a
                    // device-specific decode failure; capture details (benign tail slivers < the
                    // threshold are skipped quietly as before).
                    val expectedMs = minOf(window.endMs, durationMs ?: window.endMs) - window.startMs
                    val suspicious = expectedMs >= EMPTY_DIAG_MIN_MS
                    if (suspicious) {
                        emptyDiag.append(
                            "• win=$index [${window.startMs}–${window.endMs}ms] 預期≈${expectedMs / 1000}s 卻空 → " +
                                "${stats.summary()}\n",
                        )
                    }
                    if (planned == null) break // unknown duration: empty slice = past end-of-stream
                    // Don't advance the resume checkpoint past a window that should have held audio but
                    // decoded empty (a likely decode failure): persisting it makes a retry skip the
                    // window and never recover — the poisoned-checkpoint bug that silently masked every
                    // earlier decode fix. Benign tail slivers (< threshold) still advance so a silent
                    // end doesn't re-decode on every resume.
                    if (!suspicious) {
                        lastCompleted = index + 1
                        onCheckpoint(lastCompleted, total, render(parts, detected), detected)
                    }
                    index++
                    continue
                }
                maxSliceSamples = maxOf(maxSliceSamples, slice.size)

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
                // TEMP DEBUG: raw sherpa output before merge/OpenCC. Remove before release.
                Log.d(
                    "SherpaDbg",
                    "model=$model win=$index slice=${slice.size} samples (${slice.size / AudioToPcm.TARGET_SAMPLE_RATE}s) " +
                        "svLang='${senseVoiceLang(knownLanguage)}' knownLang=$knownLanguage " +
                        "=> rawText='${text}' rawLang='${lang}' textLen=${text.length}",
                )

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
                index++
            }
            onProgress(1f)
            // Empty final transcript: surface an actionable diagnostic instead of silently saving a
            // blank "success", so the decode-empty vs model-empty split is visible on-device.
            if (render(parts, detected).isBlank()) {
                error(emptyResultMessage(emptyDiag, maxSliceSamples))
            }
            result(parts, detected, lastCompleted, maxOf(total, lastCompleted), cancelled = false)
        } finally {
            recognizer.release()
        }
    }

    /** Builds the on-device failure message for an empty transcript (decode-empty vs model-empty). */
    private fun emptyResultMessage(emptyDiag: StringBuilder, maxSliceSamples: Int): String = buildString {
        append("轉錄結果為空。")
        if (emptyDiag.isNotEmpty()) {
            append("偵測到應有聲音卻解碼為空的視窗（疑似裝置解碼層）：\n")
            append(emptyDiag)
        } else {
            val secs = maxSliceSamples / AudioToPcm.TARGET_SAMPLE_RATE
            append("音訊已解碼（最長視窗 $maxSliceSamples samples ≈ ${secs}s）但模型輸出為空（疑似模型／ONNX 層）。")
        }
    }

    /** Builds the OfflineRecognizer config for [model], pointing it at the extracted files in [dir]. */
    private fun configFor(
        model: SherpaModel,
        dir: File,
        knownLanguage: String?,
        threads: Int,
    ): OfflineRecognizerConfig {
        val mc = OfflineModelConfig(numThreads = threads, provider = "cpu")
        when (model) {
            SherpaModel.SENSE_VOICE -> {
                mc.senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(dir, "model.int8.onnx").absolutePath,
                    language = senseVoiceLang(knownLanguage),
                    useInverseTextNormalization = true,
                )
                mc.tokens = File(dir, "tokens.txt").absolutePath
            }
            SherpaModel.PARAFORMER -> {
                mc.paraformer = OfflineParaformerModelConfig(
                    model = File(dir, "model.int8.onnx").absolutePath,
                )
                mc.tokens = File(dir, "tokens.txt").absolutePath
            }
            SherpaModel.QWEN3 -> {
                // Qwen3-ASR is a multi-file model (no tokens.txt): conv frontend + encoder + decoder +
                // an HF-style tokenizer directory. Filenames match the sherpa-onnx packaging.
                mc.qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = File(dir, "conv_frontend.onnx").absolutePath,
                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    tokenizer = File(dir, "tokenizer").absolutePath,
                )
            }
        }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioToPcm.TARGET_SAMPLE_RATE, featureDim = 80),
            modelConfig = mc,
            decodingMethod = "greedy_search",
        )
    }

    /** Maps a forced ISO language to a SenseVoice hint; unsupported/auto ⇒ "" (SenseVoice auto). */
    private fun senseVoiceLang(knownLanguage: String?): String = when (knownLanguage?.lowercase()) {
        "zh" -> "zh"
        "en" -> "en"
        "ja" -> "ja"
        "ko" -> "ko"
        "yue" -> "yue"
        else -> ""
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
        const val EMPTY_DIAG_MIN_MS = 3_000L // below this, an empty slice is a benign tail sliver
    }
}
