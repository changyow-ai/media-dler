package com.changyow.mediadler.transcribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads and extracts sherpa-onnx ASR models under `filesDir/models/sherpa/<archive>/`. Mirrors
 * [WhisperModelManager] (per-model lock, `.part` temp, progress, truncation guard) but the payload is
 * a `.tar.bz2`: it's streamed to a temp file, then extracted, and a `.complete` marker is written
 * last so a half-extracted dir is never mistaken for a usable model. Models are never bundled in APK.
 */
class SherpaModelManager(private val context: Context) {

    private val root: File get() = File(context.filesDir, "models/sherpa").apply { mkdirs() }

    // Per-model lock so a Settings "download" tap and a job's ensure() for the SAME model can't both
    // download/extract at once; different models still proceed independently.
    private val locks = ConcurrentHashMap<SherpaModel, Mutex>()
    private fun lockFor(model: SherpaModel): Mutex = locks.getOrPut(model) { Mutex() }

    /** Extracted model directory (its files are what the engine points OfflineRecognizer at). */
    fun dir(model: SherpaModel): File = File(root, model.archiveName)

    private fun marker(model: SherpaModel): File = File(dir(model), ".complete")

    /** True only once the archive has fully extracted (marker present). */
    fun isDownloaded(model: SherpaModel): Boolean = marker(model).exists()

    /** On-disk size of the extracted model, or 0 if absent. */
    fun sizeBytes(model: SherpaModel): Long =
        dir(model).takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** Removes the model (and any leftover partial download). Returns true if nothing remains. */
    fun delete(model: SherpaModel): Boolean {
        File(root, "${model.archiveName}.part").delete()
        val d = dir(model)
        return !d.exists() || d.deleteRecursively()
    }

    /** Ensures [model] is extracted, downloading + unpacking on first use. [onProgress] is 0f..1f. */
    suspend fun ensure(model: SherpaModel, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            lockFor(model).withLock {
                val target = dir(model)
                if (isDownloaded(model)) return@withLock target

                // Drop any half-extracted leftovers before a fresh attempt.
                target.deleteRecursively()
                val part = File(root, "${model.archiveName}.part")
                try {
                    // Download dominates; reserve the last 10% for extraction.
                    download(model, part) { onProgress(it * 0.9f) }
                    extract(part, root) { onProgress(0.9f + it * 0.1f) }
                    check(marker(model).createNewFile() || marker(model).exists()) {
                        "failed to finalise ${model.archiveName}"
                    }
                    onProgress(1f)
                } catch (t: Throwable) {
                    target.deleteRecursively()
                    throw t
                } finally {
                    part.delete()
                }
                target
            }
        }

    /** Streams [model] to [part], failing on a bad/truncated response (cleanup left to the caller). */
    private fun download(model: SherpaModel, part: File, onProgress: (Float) -> Unit) {
        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) error("下載模型失敗（HTTP $code）")
            val expected = conn.contentLengthLong.takeIf { it > 0 } ?: model.approxBytes
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
            val declared = conn.contentLengthLong
            if (declared > 0 && total < declared) error("模型下載不完整（$total/$declared bytes）")
            if (total < model.approxBytes / 2) error("模型下載不完整（$total bytes）")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Extracts a `.tar.bz2` into [destRoot], preserving the archive's own top-level dir. Progress is
     * the fraction of *compressed* bytes consumed — good enough for a bar. Guards against zip-slip.
     */
    private fun extract(archive: File, destRoot: File, onProgress: (Float) -> Unit) {
        val totalBytes = archive.length().coerceAtLeast(1)
        val counting = object : FilterInputStream(archive.inputStream().buffered()) {
            private var read = 0L
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    read += n
                    onProgress((read.toFloat() / totalBytes).coerceIn(0f, 1f))
                }
                return n
            }
        }
        val rootPrefix = destRoot.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(counting as InputStream)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(destRoot, entry.name)
                if (!out.canonicalPath.startsWith(rootPrefix)) error("unsafe tar entry: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                }
            }
        }
    }
}
