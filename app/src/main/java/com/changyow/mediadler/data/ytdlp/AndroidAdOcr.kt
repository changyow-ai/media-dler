package com.changyow.mediadler.data.ytdlp

import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Job-private file exchange. No network listener, external OCR executable or cloud. */
internal class AndroidAdOcr(private val directory: File) : Closeable {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val seen = mutableSetOf<String>()
    private var unavailable: String? = null

    // Native stdout and stderr have separate reader threads.
    @Synchronized fun handle(line: String): Boolean {
        val match = REQUEST.matchEntire(line) ?: return false
        val nonce = match.groupValues[1]
        if (!seen.add(nonce)) return true
        val input = File(directory, "ocr-$nonce.png")
        val output = File(directory, "ocr-$nonce.json")
        val response = try {
            check(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(input.toPath()) &&
                !Files.isSymbolicLink(output.toPath()) && input.parentFile?.canonicalFile == directory.canonicalFile &&
                input.isFile && input.length() in 24..32L * 1024 * 1024) { "invalid_ocr_input" }
            unavailable?.let { error(it) }
            recognize(input)
        } catch (error: Exception) {
            // Stop launching recognitions after a timeout: Tasks.await does not cancel the task.
            unavailable = "ocr_provider_unavailable:${error.javaClass.simpleName}"
            buildJsonObject { put("error", unavailable) }
        }
        val temporary = File(directory, "ocr-$nonce.json.tmp")
        check(!Files.isSymbolicLink(temporary.toPath()))
        FileOutputStream(temporary).use { stream ->
            stream.write(response.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
        Files.move(temporary.toPath(), output.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    private fun recognize(file: File) = run {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth in 1..1920 && bounds.outHeight in 1..4096 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000) { "invalid_ocr_dimensions" }
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "invalid_ocr_png" }
        val task = try { recognizer.process(InputImage.fromBitmap(bitmap, 0)) }
        catch (error: Exception) { bitmap.recycle(); throw error }
        // Recycle only after the underlying task completes, including an await timeout.
        task.addOnCompleteListener(Executor { it.run() }) { bitmap.recycle() }
        val text = Tasks.await(task, 20, TimeUnit.SECONDS)
        buildJsonObject {
            put("engine", WeightedHlsAds.OCR_ENGINE)
            put("observations", buildJsonArray {
                text.textBlocks.flatMap { it.lines }.take(64).forEach { line ->
                    val rect = line.boundingBox ?: return@forEach
                    val confidence = line.confidence
                    if (!confidence.isFinite() || confidence !in 0f..1f || line.text.isBlank()) return@forEach
                    val left = rect.left.coerceIn(0, bounds.outWidth)
                    val top = rect.top.coerceIn(0, bounds.outHeight)
                    val right = rect.right.coerceIn(left, bounds.outWidth)
                    val bottom = rect.bottom.coerceIn(top, bounds.outHeight)
                    if (right == left || bottom == top) return@forEach
                    add(buildJsonObject {
                        put("text", line.text.take(512))
                        put("confidence", confidence)
                        put("box", JsonArray(listOf(left.toDouble() / bounds.outWidth,
                            top.toDouble() / bounds.outHeight, (right - left).toDouble() / bounds.outWidth,
                            (bottom - top).toDouble() / bounds.outHeight).map { JsonPrimitive(it) }))
                    })
                }
            })
        }
    }

    override fun close() { recognizer.close() }

    private companion object {
        val REQUEST = Regex("HLS_AD_OCR ([0-9a-f]{32})")
    }
}
