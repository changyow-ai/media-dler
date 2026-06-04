package com.changyow.mediadler.transcribe

import android.content.Context
import com.changyow.mediadler.core.model.TranscribeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** A downloadable ggml whisper model. Multilingual variants (no `.en`) so language auto-detect works. */
enum class WhisperModel(val id: String, val fileName: String, val url: String, val approxBytes: Long) {
    BASE("base", "ggml-base.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin", 147_951_465L),
    SMALL("small", "ggml-small.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin", 487_601_967L);

    companion object {
        /** Maps the user-facing [TranscribeModel] setting to its concrete ggml model. */
        fun of(choice: TranscribeModel): WhisperModel = when (choice) {
            TranscribeModel.BASE -> BASE
            TranscribeModel.SMALL -> SMALL
        }
    }
}

/** Downloads and caches ggml models under the app's files dir. Models are never bundled in the APK. */
class WhisperModelManager(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    // Per-model lock so a Settings "download" tap and a job's ensure() for the SAME model can't both
    // write the same .part and corrupt it; different models still download independently.
    private val locks = ConcurrentHashMap<WhisperModel, Mutex>()
    private fun lockFor(model: WhisperModel): Mutex = locks.getOrPut(model) { Mutex() }

    fun file(model: WhisperModel): File = File(dir, model.fileName)

    fun isDownloaded(model: WhisperModel): Boolean {
        val f = file(model)
        // Guard against a truncated partial that was renamed: require a plausible size.
        return f.exists() && f.length() > model.approxBytes / 2
    }

    /** On-disk size of the finalised model, or 0 if absent. */
    fun sizeBytes(model: WhisperModel): Long =
        file(model).takeIf { it.exists() }?.length() ?: 0L

    /** Removes the model (and any leftover partial). Returns true if nothing remains afterwards. */
    fun delete(model: WhisperModel): Boolean {
        File(dir, "${model.fileName}.part").delete()
        val f = file(model)
        return !f.exists() || f.delete()
    }

    /** Ensures [model] is present, downloading on first use. [onProgress] is 0f..1f, best-effort. */
    suspend fun ensure(model: WhisperModel, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            lockFor(model).withLock {
                val target = file(model)
                if (isDownloaded(model)) return@withLock target

                val part = File(dir, "${model.fileName}.part")
                try {
                    download(model, part, onProgress)
                    check(part.renameTo(target)) { "failed to finalise ${model.fileName}" }
                } catch (t: Throwable) {
                    // Never leave a half-written .part to be reused or mistaken for progress.
                    part.delete()
                    throw t
                }
                target
            }
        }

    /** Streams [model] to [part], failing (and leaving cleanup to the caller) on a bad/truncated response. */
    private fun download(model: WhisperModel, part: File, onProgress: (Float) -> Unit) {
        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) error("下載模型失敗（HTTP $code）")
            val expected = conn.contentLength.toLong().takeIf { it > 0 } ?: model.approxBytes
            var total = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        total += n
                        onProgress((total.toFloat() / expected).coerceIn(0f, 1f))
                    }
                }
            }
            // Reject a truncated download (dropped connection) so a corrupt model is never promoted.
            val declared = conn.contentLength.toLong()
            if (declared > 0 && total < declared) error("模型下載不完整（$total/$declared bytes）")
            if (total < model.approxBytes / 2) error("模型下載不完整（$total bytes）")
        } finally {
            conn.disconnect()
        }
    }
}
