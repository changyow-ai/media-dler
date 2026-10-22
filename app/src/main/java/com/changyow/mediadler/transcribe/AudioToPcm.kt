package com.changyow.mediadler.transcribe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.transcribe.AudioDecodeFallback
import com.changyow.mediadler.core.transcribe.FfmpegPcm
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Decodes any container/codec Android can read (mp4/m4a/aac/opus/mp3/…) into 16 kHz mono float PCM
 * for ASR. The normal path uses [MediaExtractor]/[MediaCodec]; an empty result or decoder error
 * retries the same range with the FFmpeg runtime already bundled for downloads, entirely offline.
 * Silence is nonempty PCM and does not trigger a retry.
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
        stats: DecodeStats? = null,
    ): FloatArray = withContext(Dispatchers.IO) {
        require(startMs >= 0 && endMs > startMs) { "invalid audio range [$startMs, $endMs)" }
        AudioDecodeFallback.decode(
            primary = { decodePlatformRange(context, uri, startMs, endMs, stats) },
            fallback = {
                stats?.fallbackUsed = true
                decodeWithFfmpeg(context, uri, startMs, endMs).also { stats?.fallbackSamples = it.size }
            },
        )
    }

    /** Kept separate so platform resources are released before the independent decoder starts. */
    private suspend fun decodePlatformRange(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        stats: DecodeStats?,
    ): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            stats?.trackCount = extractor.trackCount
            stats?.trackMimes = (0 until extractor.trackCount).joinToString(",") {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "?"
            }
            val track = audioTrack(extractor) ?: error("no audio track in $uri")
            extractor.selectTrack(track)
            stats?.selectedTrack = track

            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val startUs = if (startMs > 0) startMs * 1000 else 0L
            val endUs = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else endMs * 1000
            stats?.apply {
                this.startUs = startUs
                this.endUs = endUs
                inputMime = mime
                inputSampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)
                inputChannels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 0)
                trackDurationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    inputFormat.getLong(MediaFormat.KEY_DURATION)
                } else {
                    -1
                }
                hasCsd0 = inputFormat.containsKey("csd-0")
            }
            // A reported S23U failure fed no packets. Retry positioning without assuming its cause
            // is the model or the container edit list. Presence is determined by the track index:
            // negative timestamps can be valid decoder pre-roll, not necessarily end-of-stream.
            positionExtractor(extractor, startMs, startUs, stats)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                stats?.codecName = runCatching { codec.name }.getOrDefault("?")
                val decoded = drainToMonoFloat(extractor, codec, inputFormat, startUs, endUs, stats)
                return resampleTo16k(decoded.samples, decoded.sampleRate)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Decode local files only; content URI grants are used through ContentResolver, never sent out. */
    private suspend fun decodeWithFfmpeg(context: Context, uri: Uri, startMs: Long, endMs: Long): FloatArray {
        val scratch = File(context.cacheDir, "transcribe/pcm-${UUID.randomUUID()}")
        check(scratch.mkdirs()) { "無法建立音訊解碼暫存目錄" }
        try {
            val input = when (uri.scheme) {
                null, "file" -> File(requireNotNull(uri.path) { "missing audio file path" })
                "content" -> File(scratch, "input").also { copy ->
                    context.contentResolver.openInputStream(uri).use { source ->
                        requireNotNull(source) { "無法讀取音訊" }
                        copy.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                else -> error("音訊解碼只接受本機檔案")
            }
            val output = File(scratch, "audio.pcm")
            val decoded = withTimeoutOrNull(180_000L) {
                context.appContainer.engine.decodeAudioRange(input, output, startMs, endMs)
                true
            }
            check(decoded == true) { "音訊解碼逾時，已保留輸入檔案，請重試。" }
            val maxSamples = if (endMs == Long.MAX_VALUE) FfmpegPcm.MAX_EOF_SAMPLES else
                ((endMs - startMs) * TARGET_SAMPLE_RATE / 1000 + TARGET_SAMPLE_RATE)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return FfmpegPcm.read(output, maxSamples)
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    /**
     * Positions [extractor]'s read cursor at [startUs] so the decode loop can read samples. Tries the
     * unseeked cursor first (keeps the working path unchanged for normal files), then explicit seeks
     * with each sync mode, stopping at the first that exposes a real sample. The chosen strategy and
     * the resulting [MediaExtractor.getSampleTime] are recorded so a single on-device failure makes
     * a positioning failure diagnosable without logcat.
     */
    private fun positionExtractor(extractor: MediaExtractor, startMs: Long, startUs: Long, stats: DecodeStats?) {
        // Unseeked cursor — only valid as a strategy at the very start (no seek was requested).
        if (startMs == 0L && extractor.sampleTrackIndex >= 0) {
            stats?.let { it.seekStrategy = "none"; it.firstSampleTimeUs = extractor.sampleTime }
            return
        }
        // Decode pre-roll and trim it rather than jumping past the first samples of the range.
        val modes = listOf(
            "prev" to MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
            "closest" to MediaExtractor.SEEK_TO_CLOSEST_SYNC,
            "next" to MediaExtractor.SEEK_TO_NEXT_SYNC,
        )
        for ((name, mode) in modes) {
            extractor.seekTo(startUs, mode)
            val t = extractor.sampleTime
            if (extractor.sampleTrackIndex >= 0) {
                stats?.let { it.seekStrategy = name; it.firstSampleTimeUs = t }
                return
            }
        }
        // Do not skip the first 50 ms to coax this extractor into producing samples: that can
        // discard the only consonant in a very short utterance. The independent decoder retries
        // the original full range instead.
        // Nothing exposed a sample; leave the cursor where the last seek left it and let the loop
        // report the empty decode. firstSampleTimeUs stays at the sentinel so the dump shows "no sample".
        stats?.seekStrategy = "none-worked"
    }

    private class Decoded(val samples: FloatArray, val sampleRate: Int)

    /**
     * Optional decode telemetry for on-device debugging — logcat is unavailable on locked-down
     * devices (e.g. the S23U short-clip empty-transcript bug). Pass a non-null instance to
     * [decodeRange] and it is filled during the decode; [summary] renders a one-line dump that can
     * be surfaced in an on-screen error message. A telling pattern for "fed input but got no PCM" is
     * `fed>0 / rawBytes=0 / eosBeforeData=true` (decoder flushed the span without draining it).
     */
    class DecodeStats {
        var codecName: String = "?"
        var inputMime: String = "?"
        var inputSampleRate: Int = 0
        var inputChannels: Int = 0
        var outputSampleRate: Int = 0
        var outputChannels: Int = 0
        var pcmEncoding: Int = 0
        var formatChanged: Boolean = false
        var inputBuffersFed: Int = 0
        var inputBytesFed: Long = 0
        var outputBuffers: Int = 0
        var rawBytes: Int = 0
        var firstOutPtsUs: Long = -1
        var lastOutPtsUs: Long = -1
        var startUs: Long = 0
        var endUs: Long = 0
        var sawInputEos: Boolean = false
        var sawOutputEos: Boolean = false
        var eosOutputBeforeData: Boolean = false
        // Container / positioning telemetry — added to pin down the edit-list "no samples" case where
        // the decoder is never fed (fed=0). seekStrategy says which cursor position exposed a sample.
        var trackCount: Int = 0
        var selectedTrack: Int = -1
        var trackMimes: String = "?"
        var trackDurationUs: Long = -1
        var hasCsd0: Boolean = false
        var seekStrategy: String = "?"
        var firstSampleTimeUs: Long = Long.MIN_VALUE
        var fallbackUsed: Boolean = false
        var fallbackSamples: Int = 0

        fun summary(): String =
            "codec=$codecName mime=$inputMime in=${inputSampleRate}Hz/${inputChannels}ch " +
                "out=${outputSampleRate}Hz/${outputChannels}ch/enc$pcmEncoding fmtChg=$formatChanged " +
                "tracks=$trackCount/sel=$selectedTrack[$trackMimes] durUs=$trackDurationUs csd0=$hasCsd0 " +
                "seek=$seekStrategy firstSample=${firstSampleTimeUs}us " +
                "fed=${inputBuffersFed}buf/${inputBytesFed}B outBuf=$outputBuffers rawBytes=$rawBytes " +
                "outPts=$firstOutPtsUs..${lastOutPtsUs}us inEos=$sawInputEos outEos=$sawOutputEos " +
                "eosBeforeData=$eosOutputBeforeData ffmpeg=$fallbackUsed/$fallbackSamples samples"
    }

    /**
     * Runs the decode loop until end-of-stream or the first decoded buffer at/after [endUs],
     * returning mono float samples still at the source sample rate. Leading frames before [startUs]
     * are trimmed: SEEK_TO_CLOSEST_SYNC can land seconds before the requested window start, and
     * keeping that pre-roll would inflate the inter-window overlap beyond what [SegmentMerge] dedups.
     */
    private suspend fun drainToMonoFloat(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        startUs: Long,
        endUs: Long,
        stats: DecodeStats? = null,
    ): Decoded {
        val raw = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var sampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
        var channels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var pcmEncoding = inputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT)
        val timeoutUs = 10_000L

        var lastActivityMs = SystemClock.elapsedRealtime()
        while (!sawOutputEos) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() - lastActivityMs < 30_000L) {
                "音訊解碼器逾時，重試其他解碼器"
            }
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val sampleTime = extractor.sampleTime
                    // Stop feeding once we've passed the requested span; flush with EOS.
                    if (extractor.sampleTrackIndex < 0 || (endUs != Long.MAX_VALUE && sampleTime >= endUs)) {
                        // No data was queued for this span (empty window); send a bare EOS to drain.
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                        stats?.sawInputEos = true
                    } else {
                        val inBuf = codec.getInputBuffer(inIndex)!!.also { it.clear() }
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                            stats?.sawInputEos = true
                        } else {
                            val hasNext = extractor.advance()
                            // Look ahead: if the next sample is past the span (or EOF), THIS is the last
                            // data buffer, so carry the EOS flag on it instead of queuing a separate
                            // zero-size EOS buffer. Some decoders (notably Samsung) don't flush pending
                            // output on a bare EOS buffer, which strands the whole transcript for short
                            // clips where every frame is still buffered when EOS arrives.
                            val nextTime = extractor.sampleTime
                            val isLast = !hasNext || (endUs != Long.MAX_VALUE && nextTime >= endUs)
                            val flags = if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            codec.queueInputBuffer(inIndex, 0, size, sampleTime, flags)
                            lastActivityMs = SystemClock.elapsedRealtime()
                            stats?.let { it.inputBuffersFed++; it.inputBytesFed += size }
                            if (isLast) {
                                sawInputEos = true
                                stats?.sawInputEos = true
                            }
                        }
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    lastActivityMs = SystemClock.elapsedRealtime()
                    val f = codec.outputFormat
                    sampleRate = f.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = f.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    pcmEncoding = f.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                    stats?.formatChanged = true
                }
                outIndex >= 0 -> {
                    lastActivityMs = SystemClock.elapsedRealtime()
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (info.size > 0) {
                        // Drop pre-roll decoded before the requested window start (seek slop).
                        val bytesPerFrame = channels.coerceAtLeast(1) * bytesPerSample(pcmEncoding)
                        val skip = if (info.presentationTimeUs < startUs && bytesPerFrame > 0) {
                            val framesAhead = (startUs - info.presentationTimeUs) * sampleRate / 1_000_000L
                            (framesAhead * bytesPerFrame).coerceIn(0L, info.size.toLong()).toInt()
                        } else {
                            0
                        }
                        val available = if (endUs != Long.MAX_VALUE) {
                            val framesBeforeEnd = ((endUs - info.presentationTimeUs).coerceAtLeast(0L) *
                                sampleRate / 1_000_000L)
                            (framesBeforeEnd * bytesPerFrame).coerceAtMost(info.size.toLong()).toInt()
                        } else info.size
                        if (skip < available) {
                            val len = available - skip
                            val chunk = ByteArray(len)
                            outBuf.position(info.offset + skip)
                            outBuf.get(chunk, 0, len)
                            raw.write(chunk)
                            stats?.let {
                                it.outputBuffers++
                                if (it.firstOutPtsUs < 0) it.firstOutPtsUs = info.presentationTimeUs
                                it.lastOutPtsUs = info.presentationTimeUs
                            }
                        }
                    }
                    val pastEnd = endUs != Long.MAX_VALUE && info.presentationTimeUs >= endUs
                    val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (isEos) stats?.let {
                        it.sawOutputEos = true
                        // EOS arriving with no data collected yet pinpoints a decoder that flushed the
                        // span without draining it — the prime suspect for the S23U empty-window bug.
                        if (raw.size() == 0) it.eosOutputBeforeData = true
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (isEos || pastEnd) {
                        sawOutputEos = true
                    }
                }
            }
        }

        stats?.let {
            it.outputSampleRate = sampleRate
            it.outputChannels = channels
            it.pcmEncoding = pcmEncoding
            it.rawBytes = raw.size()
        }
        val mono = toMonoFloat(raw.toByteArray(), channels, pcmEncoding)
        return Decoded(mono, sampleRate)
    }

    /** Interleaved PCM bytes → mono float in [-1, 1]. Supports 16-bit int and float PCM. */
    private fun toMonoFloat(bytes: ByteArray, channels: Int, pcmEncoding: Int): FloatArray {
        require(pcmEncoding == ENCODING_PCM_16BIT || pcmEncoding == ENCODING_PCM_FLOAT) {
            "unsupported PCM encoding: $pcmEncoding"
        }
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
