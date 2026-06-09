package com.changyow.mediadler.transcribe

import android.content.Context
import android.net.Uri
import com.changyow.mediadler.core.model.CloudTranscribeConfig
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.core.transcribe.SegmentMerge
import com.changyow.mediadler.core.transcribe.Transcript
import com.changyow.mediadler.core.transcribe.WindowPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Cloud [StreamingEngine] for OpenRouter's `/audio/transcriptions` endpoint (JSON body with base64
 * audio — OpenRouter does not accept OpenAI-style multipart). Config (base URL, API key, model) comes
 * from [settings] and is entered by the user — never bundled. Long audio is cut into [WindowPlanner]
 * windows sized so each chunk stays under the provider's upload limit; every window is decoded with
 * [AudioToPcm], encoded to a small 16 kHz mono WAV, POSTed, then deleted. Each window is one
 * checkpoint, so a killed job resumes mid-file and the partial text already uploaded is kept.
 */
class CloudTranscriptionEngine(
    private val context: Context,
    private val settings: SettingsRepository,
) : StreamingEngine {

    override val id = "cloud"

    override suspend fun transcribeStreaming(
        audioUri: String,
        startWindow: Int,
        priorText: String,
        knownLanguage: String?,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onCheckpoint: (Int, Int, String, String?) -> Unit,
    ): StreamResult = withContext(Dispatchers.IO) {
        val config = settings.settings.first().cloud
        require(config.isConfigured) { "雲端引擎尚未設定（缺少 API 位址、金鑰或模型）" }
        val uri = Uri.parse(audioUri)

        // Window the audio so every uploaded chunk stays under the provider's size limit / timeout.
        // Raw WAV is bulky (base64 inflates it ~33 %), so WAV mode uses a short window; compressed
        // (m4a) windows are tiny and can run long. When the duration is unknown we stream open-ended
        // windows and stop at the first empty slice, rather than decoding the whole file into one
        // over-cap (and OOM-prone) upload.
        val windowMs = if (config.compressAudio) COMPRESSED_WINDOW_MS else WAV_WINDOW_MS
        val durationMs = AudioToPcm.durationMs(context, uri)
        val planned = durationMs?.let { WindowPlanner.plan(it, windowMs, OVERLAP_MS) }
        val total = planned?.size ?: 0 // 0 ⇒ unknown duration (open-ended)
        // Self-heal a poisoned checkpoint that points past the last window (see SherpaOnnxEngine).
        val effectiveStart = if (planned != null && startWindow >= planned.size && priorText.isBlank()) 0 else startWindow

        val parts = ArrayList<String>()
        if (priorText.isNotBlank()) parts.add(priorText)
        var detected: String? = knownLanguage
        var lastCompleted = effectiveStart

        val scratch = File(context.cacheDir, "transcribe/cloud").apply { mkdirs() }
        try {
            var index = effectiveStart
            while (planned == null || index < planned.size) {
                if (isCancelled()) {
                    return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                }
                onProgress(fractionAt(index, total))
                val window = planned?.get(index) ?: WindowPlanner.openWindow(index, windowMs, OVERLAP_MS)
                // The final (or only) planned window decodes to natural EOF: the floored-to-ms endUs
                // cutoff can race the last frames on short clips.
                val decodeEndMs = if (planned != null && index == planned.lastIndex) Long.MAX_VALUE else window.endMs
                val pcm = AudioToPcm.decodeRange(context, uri, window.startMs, decodeEndMs)
                if (pcm.isEmpty()) {
                    if (planned == null) break // unknown duration: empty slice = past end-of-stream
                    // Don't advance the resume checkpoint past a window that should have held audio but
                    // decoded empty (likely a decode failure) — persisting it makes a retry skip the
                    // window forever (poisoned-checkpoint bug). Benign tail slivers still advance.
                    val expectedMs = minOf(window.endMs, durationMs ?: window.endMs) - window.startMs
                    if (expectedMs < EMPTY_WINDOW_SKIP_MS) {
                        lastCompleted = index + 1
                        onCheckpoint(lastCompleted, total, render(parts, detected), detected)
                    }
                    index++
                    continue
                }
                val format = if (config.compressAudio) "m4a" else "wav"
                val chunk = File(scratch, "chunk-$index.$format")
                try {
                    if (config.compressAudio) AudioEncoder.encodeM4a(chunk, pcm) else writeWav(chunk, pcm)
                    // runInterruptible so service teardown (scope.cancel) actually aborts the blocking
                    // upload/read instead of leaking the connection until readTimeout.
                    val response = runInterruptible { postTranscription(config, chunk, format, knownLanguage) }
                    if (isCancelled()) {
                        return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                    }
                    if (detected == null) detected = response.language?.ifBlank { null }
                    parts.add(response.text.trim())
                } finally {
                    chunk.delete()
                }
                lastCompleted = index + 1
                val merged = render(parts, detected)
                onProgress(fractionAt(index + 1, total))
                onPartial(merged)
                onCheckpoint(lastCompleted, total, merged, detected)
                index++
            }
            onProgress(1f)
            result(parts, detected, lastCompleted, maxOf(total, lastCompleted), cancelled = false)
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * Global progress fraction at window [index]. With a known [total] it is linear; with an unknown
     * total (0) it asymptotes toward 1 so the bar advances without ever falsely reading complete.
     */
    private fun fractionAt(index: Int, total: Int): Float =
        if (total > 0) (index.toFloat() / total).coerceIn(0f, 1f) else 1f - 1f / (index + 1f)

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

    /** Merges window parts, then normalises Chinese to 正體 (same post-processing as on-device). */
    private fun render(parts: List<String>, language: String?): String =
        OpenCcConverter.normalize(SegmentMerge.merge(parts), language)

    // OpenRouter returns { "text": ..., "usage": {...} } — no "language" field, so detection relies on
    // the user's forced language (or stays null, leaving Chinese 正體 normalisation off for AUTO).
    @Serializable
    private data class TranscriptionResponse(val text: String = "", val language: String? = null)

    /**
     * POSTs [wav] to OpenRouter's `{baseUrl}/audio/transcriptions` as JSON with base64-encoded audio
     * (OpenRouter rejects multipart). The audio is base64-streamed in 3-byte-aligned blocks so a
     * ~19 MB window is never materialised as one giant in-memory string. Returns the parsed transcript.
     */
    private fun postTranscription(
        config: CloudTranscribeConfig,
        audio: File,
        format: String,
        language: String?,
    ): TranscriptionResponse {
        val endpoint = "${config.baseUrl.trimEnd('/')}/audio/transcriptions"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.buffered().use { raw ->
                val out = raw.writer(Charsets.UTF_8)
                out.write("{\"model\":${JsonPrimitive(config.model)}")
                if (!language.isNullOrBlank()) out.write(",\"language\":${JsonPrimitive(language)}")
                out.write(",\"input_audio\":{\"format\":${JsonPrimitive(format)},\"data\":\"")
                val encoder = Base64.getEncoder()
                audio.inputStream().buffered().use { input ->
                    val block = ByteArray(3 * 8192) // multiple of 3 ⇒ no padding mid-stream
                    while (true) {
                        val n = readBlock(input, block)
                        if (n <= 0) break
                        out.write(encoder.encodeToString(if (n == block.size) block else block.copyOf(n)))
                        if (n < block.size) break
                    }
                }
                out.write("\"}}")
                out.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("雲端轉錄失敗（HTTP $code）：${err.take(300)}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString(TranscriptionResponse.serializer(), body)
        } finally {
            conn.disconnect()
        }
    }

    /** Fills [buf] from [input], coalescing short reads; returns bytes read (0 at a clean EOF). */
    private fun readBlock(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    /** Writes mono 16 kHz [samples] as a 16-bit PCM WAV (the universally accepted upload format). */
    private fun writeWav(file: File, samples: FloatArray) {
        val sampleRate = AudioToPcm.TARGET_SAMPLE_RATE
        val dataSize = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)                 // PCM fmt chunk size
        header.putShort(1)                // audio format = PCM
        header.putShort(1)                // channels = mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)     // byte rate = rate * channels * bytesPerSample
        header.putShort(2)                // block align = channels * bytesPerSample
        header.putShort(16)               // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)

        file.outputStream().buffered().use { out ->
            out.write(header.array())
            val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                buf.putShort(v)
            }
            out.write(buf.array())
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        // WAV: 5 min @ 16 kHz mono 16-bit ≈ 9.5 MB → ~12.5 MB once base64'd, safely under limits and
        // the upstream ~60 s timeout. Compressed (m4a) windows are ~1/5 the size, so they can run the
        // full default length without bloating the request body.
        const val EMPTY_WINDOW_SKIP_MS = 3_000L // empties shorter than this are benign tail slivers
        const val WAV_WINDOW_MS = 5 * 60_000L
        const val COMPRESSED_WINDOW_MS = WindowPlanner.DEFAULT_WINDOW_MS
        const val OVERLAP_MS = WindowPlanner.DEFAULT_OVERLAP_MS
    }
}
