package com.changyow.mediadler.transcribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/** A downloadable ggml whisper model. Multilingual variants (no `.en`) so language auto-detect works. */
enum class WhisperModel(val id: String, val fileName: String, val url: String, val approxBytes: Long) {
    BASE("base", "ggml-base.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin", 147_951_465L),
    SMALL("small", "ggml-small.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin", 487_601_967L),
}

/** Downloads and caches ggml models under the app's files dir. Models are never bundled in the APK. */
class WhisperModelManager(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }

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
            val target = file(model)
            if (isDownloaded(model)) return@withContext target

            val part = File(dir, "${model.fileName}.part")
            URL(model.url).openConnection().apply { connect() }.getInputStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        total += n
                        onProgress((total.toFloat() / model.approxBytes).coerceIn(0f, 1f))
                    }
                }
            }
            check(part.renameTo(target)) { "failed to finalise ${model.fileName}" }
            target
        }
}
