package com.changyow.mediadler.transcribe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Decodes any container/codec Android can read (mp4/m4a/aac/opus/mp3/…) into 16 kHz mono float PCM
 * for whisper, using the platform [MediaExtractor]/[MediaCodec] — no ffmpeg dependency. Downmix to
 * mono and linear-resample to 16 kHz happen here.
 *
 * NOTE: decodes the whole file into memory. Fine for voice messages / short clips; hour-long audio
 * should switch to time-windowed streaming decode (tracked for later, see plan WindowPlanner).
 */
object AudioToPcm {
    const val TARGET_SAMPLE_RATE = 16_000

    suspend fun decode(context: Context, uri: Uri): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track in $uri")
            extractor.selectTrack(track)

            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            try {
                val decoded = drainToMonoFloat(extractor, codec, inputFormat)
                resampleTo16k(decoded.samples, decoded.sampleRate)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private class Decoded(val samples: FloatArray, val sampleRate: Int)

    /** Runs the decode loop, returning mono float samples still at the source sample rate. */
    private fun drainToMonoFloat(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
    ): Decoded {
        val raw = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var pcmEncoding = inputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT)
        val timeoutUs = 10_000L

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = f.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    pcmEncoding = f.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                }
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(chunk, 0, info.size)
                        raw.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
        }

        val mono = toMonoFloat(raw.toByteArray(), channels, pcmEncoding)
        return Decoded(mono, sampleRate)
    }

    /** Interleaved PCM bytes → mono float in [-1, 1]. Supports 16-bit int and float PCM. */
    private fun toMonoFloat(bytes: ByteArray, channels: Int, pcmEncoding: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (pcmEncoding) {
            ENCODING_PCM_FLOAT -> {
                val fb = bb.asFloatBuffer()
                val frames = fb.remaining() / ch
                FloatArray(frames) { i ->
                    var sum = 0f
                    for (c in 0 until ch) sum += fb.get(i * ch + c)
                    sum / ch
                }
            }
            else -> { // ENCODING_PCM_16BIT
                val sb = bb.asShortBuffer()
                val frames = sb.remaining() / ch
                FloatArray(frames) { i ->
                    var sum = 0
                    for (c in 0 until ch) sum += sb.get(i * ch + c).toInt()
                    (sum.toFloat() / ch) / 32768f
                }
            }
        }
    }

    /** Linear-interpolation resample of mono [src] from [srcRate] to [TARGET_SAMPLE_RATE]. */
    private fun resampleTo16k(src: FloatArray, srcRate: Int): FloatArray {
        if (srcRate == TARGET_SAMPLE_RATE || src.isEmpty()) return src
        val ratio = TARGET_SAMPLE_RATE.toDouble() / srcRate
        val outLen = (src.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i / ratio
            val i0 = pos.toInt()
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = src[i0] * (1f - frac) + src[i1] * frac
        }
        return out
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    // From AudioFormat; inlined to avoid importing for two constants.
    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_FLOAT = 4
}
