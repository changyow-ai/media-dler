package com.changyow.mediadler.transcribe

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes 16 kHz mono PCM (float, -1..1) to an AAC-LC `.m4a` file via [MediaCodec] + [MediaMuxer].
 * Used by [CloudTranscriptionEngine] when the user opts into compressed upload: an m4a window is
 * roughly a fifth of the equivalent WAV, so it uploads faster and is less likely to hit OpenRouter's
 * upstream timeout on long audio. Lossy, so a small accuracy cost; cost is unchanged (billed by
 * duration). OpenRouter accepts `m4a` as an `input_audio.format`.
 */
object AudioEncoder {

    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 32_000 // 32 kbps is ample for 16 kHz mono speech
    private const val TIMEOUT_US = 10_000L

    /** Writes [samples] (16 kHz mono) as AAC-LC into [file] (overwritten). */
    fun encodeM4a(file: File, samples: FloatArray) {
        val sampleRate = AudioToPcm.TARGET_SAMPLE_RATE
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MIME).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        // Interleave float→16-bit PCM bytes once; feed to the encoder in input-buffer-sized chunks.
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcm.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        pcm.flip()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var presentationUs = 0L
        try {
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!.also { it.clear() }
                        val chunk = minOf(inBuf.capacity(), pcm.remaining())
                        if (chunk > 0) {
                            val slice = ByteArray(chunk).also { pcm.get(it) }
                            inBuf.put(slice)
                            codec.queueInputBuffer(inIndex, 0, chunk, presentationUs, 0)
                            presentationUs += (chunk / 2) * 1_000_000L / sampleRate
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        // Codec-config bytes (ESDS) are carried by the track format, not as a sample.
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
