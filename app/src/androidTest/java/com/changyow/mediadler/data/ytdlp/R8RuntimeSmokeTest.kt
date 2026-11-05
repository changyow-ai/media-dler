package com.changyow.mediadler.data.ytdlp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.appContainer
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run against Release: exercises optimized OCR and the preserved native download bridge offline. */
@RunWith(AndroidJUnit4::class)
class R8RuntimeSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 60_000) fun bundledChineseOcrRecognizesTextThroughAppBridge() {
        val directory = File(context.cacheDir, "r8-ocr-" + UUID.randomUUID()).apply { mkdirs() }
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val bitmap = Bitmap.createBitmap(1000, 220, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("中文測試 123456", 40f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 80f
                })
            }
            File(directory, "ocr-" + nonce + ".png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            AndroidAdOcr(directory).use { assertTrue(it.handle("HLS_AD_OCR " + nonce)) }
            val response = JSONObject(File(directory, "ocr-" + nonce + ".json").readText())
            assertFalse("OCR returned an error: " + response, response.has("error"))
            val observations = response.getJSONArray("observations")
            val text = (0 until observations.length()).joinToString("") {
                observations.getJSONObject(it).getString("text")
            }
            assertTrue("Expected real Chinese OCR output, got: " + text, text.contains("中文測試"))
            assertTrue("Expected numeric OCR output, got: " + text, text.contains("123456"))
        } finally {
            bitmap.recycle()
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 120_000) fun bundledPythonYtDlpAndFfmpegExecuteOffline() = runBlocking {
        withTimeout(110_000) {
            val directory = File(context.cacheDir, "r8-native-" + UUID.randomUUID()).apply { mkdirs() }
            try {
                val engine = context.appContainer.engine
                assertTrue("Bundled runtime initialization failed", engine.ensureInit() is EngineState.Ready)
                // --version exits before resolving this deliberately invalid URL.
                val version = withContext(Dispatchers.IO) {
                    engine.execute(YoutubeDLRequest("https://example.invalid/r8-smoke").apply {
                        addOption("--version")
                    }).out.trim()
                }
                assertTrue("yt-dlp did not execute: " + version, Regex("\\d{4}\\.\\d{2}\\.\\d{2}").containsMatchIn(version))

                val samples = 16_000
                val wave = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + samples * 2)
                    put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
                    putShort(1); putShort(1); putInt(16_000); putInt(32_000)
                    putShort(2); putShort(16)
                    put("data".toByteArray(Charsets.US_ASCII)); putInt(samples * 2)
                    repeat(samples) { putShort((if (it % 32 < 16) 8000 else -8000).toShort()) }
                }.array()
                val input = File(directory, "input.wav").apply { writeBytes(wave) }
                val output = File(directory, "output.pcm")
                engine.decodeAudioRange(input, output, 0, 1000)
                assertEquals("FFmpeg must decode the full one-second fixture", samples * 2L, output.length())
                assertArrayEquals(wave.copyOfRange(44, wave.size), output.readBytes())
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
