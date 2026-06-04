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
 * To keep memory bounded for hour-long audio, the engine decodes one **time window** at a time via
 * [decodeRange] (a 60 s window of 16 kHz mono float is ~3.8 MB, vs ~230 MB for a whole hour). Each
 * call seeks to the window start and decodes just that span; windows overlap slightly and the text
 * seam is de-duplicated downstream, so the coarse (frame-granularity) seek slop is harmless.
 */
object AudioToPcm {
    const val TARGET_SAMPLE_RATE = 16_000

    /** Whole-file decode (single pass). Kept for callers that don't window. */
    suspend fun decode(context: Context, uri: Uri): FloatArray =
        decodeRange(context, uri, 0L, Long.MAX_VALUE)

    /** Audio duration in ms from container metadata, or null if the track doesn't carry it. */
    suspend fun durationMs(context: Context, uri: Uri): Long? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = audioTrack(extractor) ?: return@withContext null
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                (format.getLong(MediaFormat.KEY_DURATION) / 1000).takeIf { it > 0 }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Decodes the audio span `[startMs, endMs)` to 16 kHz mono float PCM. [endMs] of
     * [Long.MAX_VALUE] decodes to the end of the stream. Only this span is held in memory.
     */
    suspend fun decodeRange(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
    ): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = audioTrack(extractor) ?: error("no audio track in $uri")
            extractor.selectTrack(track)
            if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val startUs = if (startMs > 0) startMs * 1000 else 0L
            val endUs = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else endMs * 1000
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            try {
                val decoded = drainToMonoFloat(extractor, codec, inputFormat, startUs, endUs)
                resampleTo16k(decoded.samples, decoded.sampleRate)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    private class Decoded(val samples: FloatArray, val sampleRate: Int)

    /**
     * Runs the decode loop until end-of-stream or the first decoded buffer at/after [endUs],
     * returning mono float samples still at the source sample rate. Leading frames before [startUs]
     * are trimmed: SEEK_TO_CLOSEST_SYNC can land seconds before the requested window start, and
     * keeping that pre-roll would inflate the inter-window overlap beyond what [SegmentMerge] dedups.
     */
    private fun drainToMonoFloat(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        startUs: Long,
        endUs: Long,
    ): Decoded {
        val raw = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var sampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
        var channels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var pcmEncoding = inputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT)
        val timeoutUs = 10_000L

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val sampleTime = extractor.sampleTime
                    // Stop feeding once we've passed the requested span; flush with EOS.
                    if (sampleTime < 0 || (endUs != Long.MAX_VALUE && sampleTime >= endUs)) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, sampleTime, 0)
                            extractor.advance()
                        }
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
                        // Drop pre-roll decoded before the requested window start (seek slop).
                        val bytesPerFrame = channels.coerceAtLeast(1) * bytesPerSample(pcmEncoding)
                        val skip = if (startUs > 0 && info.presentationTimeUs < startUs && bytesPerFrame > 0) {
                            val framesAhead = (startUs - info.presentationTimeUs) * sampleRate / 1_000_000L
                            (framesAhead * bytesPerFrame).coerceIn(0L, info.size.toLong()).toInt()
                        } else {
                            0
                        }
                        if (skip < info.size) {
                            val len = info.size - skip
                            val chunk = ByteArray(len)
                            outBuf.position(info.offset + skip)
                            outBuf.get(chunk, 0, len)
                            raw.write(chunk)
                        }
                    }
                    val pastEnd = endUs != Long.MAX_VALUE && info.presentationTimeUs >= endUs
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || pastEnd) {
                        sawOutputEos = true
                    }
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

    /** Bytes per sample for the PCM encodings [toMonoFloat] reads (float = 4, everything else 16-bit). */
    private fun bytesPerSample(pcmEncoding: Int): Int = if (pcmEncoding == ENCODING_PCM_FLOAT) 4 else 2

    /** Linear-interpolation resample of mono [src] from [srcRate] to [TARGET_SAMPLE_RATE]. */
    private fun resampleTo16k(src: FloatArray, srcRate: Int): FloatArray {
        if (srcRate <= 0 || srcRate == TARGET_SAMPLE_RATE || src.isEmpty()) return src
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
