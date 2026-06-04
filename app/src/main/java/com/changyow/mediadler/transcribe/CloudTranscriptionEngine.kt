package com.changyow.mediadler.transcribe

import android.content.Context
import android.net.Uri
import com.changyow.mediadler.core.model.CloudTranscribeConfig
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.core.transcribe.AudioWindow
import com.changyow.mediadler.core.transcribe.SegmentMerge
import com.changyow.mediadler.core.transcribe.Transcript
import com.changyow.mediadler.core.transcribe.WindowPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cloud [StreamingEngine] for any OpenAI-compatible `/audio/transcriptions` endpoint (OpenAI, Groq,
 * OpenRouter…). Config (base URL, API key, model) comes from [settings] and is entered by the user —
 * never bundled. Long audio is cut into [WindowPlanner] windows sized so each chunk stays under the
 * provider's upload limit; every window is decoded with [AudioToPcm], encoded to a small 16 kHz mono
 * WAV, POSTed, then deleted. Each window is one checkpoint, so a killed job resumes mid-file and the
 * partial text already uploaded is kept.
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

        // Window the audio so every uploaded WAV chunk stays under the provider's size limit.
        val durationMs = AudioToPcm.durationMs(context, uri)
        val windows = if (durationMs != null) {
            WindowPlanner.plan(durationMs, WINDOW_MS, OVERLAP_MS)
        } else {
            listOf(AudioWindow(0L, Long.MAX_VALUE))
        }
        val total = windows.size

        val parts = ArrayList<String>()
        if (priorText.isNotBlank()) parts.add(priorText)
        var detected: String? = knownLanguage
        var lastCompleted = startWindow

        val scratch = File(context.cacheDir, "transcribe/cloud").apply { mkdirs() }
        try {
            for (index in startWindow until total) {
                if (isCancelled()) {
                    return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                }
                onProgress(index.toFloat() / total)
                val window = windows[index]
                val pcm = AudioToPcm.decodeRange(context, uri, window.startMs, window.endMs)
                if (pcm.isEmpty()) {
                    lastCompleted = index + 1
                    onCheckpoint(lastCompleted, total, render(parts, detected), detected)
                    continue
                }
                val wav = File(scratch, "chunk-$index.wav")
                try {
                    writeWav(wav, pcm)
                    val response = postTranscription(config, wav, knownLanguage)
                    if (isCancelled()) {
                        return@withContext result(parts, detected, lastCompleted, total, cancelled = true)
                    }
                    if (detected == null) detected = response.language?.ifBlank { null }
                    parts.add(response.text.trim())
                } finally {
                    wav.delete()
                }
                lastCompleted = index + 1
                val merged = render(parts, detected)
                onProgress((index + 1).toFloat() / total)
                onPartial(merged)
                onCheckpoint(lastCompleted, total, merged, detected)
            }
            onProgress(1f)
            result(parts, detected, total, total, cancelled = false)
        } finally {
            scratch.deleteRecursively()
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

    /** Merges window parts, then normalises Chinese to 正體 (same post-processing as on-device). */
    private fun render(parts: List<String>, language: String?): String =
        OpenCcConverter.normalize(SegmentMerge.merge(parts), language)

    @Serializable
    private data class TranscriptionResponse(val text: String = "", val language: String? = null)

    /** Multipart POST of [wav] to `{baseUrl}/audio/transcriptions`; returns the parsed transcript. */
    private fun postTranscription(
        config: CloudTranscribeConfig,
        wav: File,
        language: String?,
    ): TranscriptionResponse {
        val endpoint = "${config.baseUrl.trimEnd('/')}/audio/transcriptions"
        val boundary = "----mediadler${wav.name.hashCode()}"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.writeBytes(value)
                    out.writeBytes("\r\n")
                }
                field("model", config.model)
                field("response_format", "verbose_json")
                if (!language.isNullOrBlank()) field("language", language)
                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${wav.name}\"\r\n",
                )
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                wav.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
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
        // 10 min @ 16 kHz mono 16-bit ≈ 19 MB WAV — under the common 25 MB upload cap.
        const val WINDOW_MS = WindowPlanner.DEFAULT_WINDOW_MS
        const val OVERLAP_MS = WindowPlanner.DEFAULT_OVERLAP_MS
    }
}
