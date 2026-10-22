package com.changyow.mediadler.core.transcribe

import java.io.DataInputStream
import java.io.File

/** Local, software-decoded PCM fallback. No container parser or Android codec is needed to read it. */
object FfmpegPcm {
    const val SAMPLE_RATE = 16_000
    const val MAX_EOF_SAMPLES = SAMPLE_RATE * 10 * 60

    /** Arguments only, without the executable. Seeking remains sample-accurate when transcoding. */
    fun arguments(input: File, output: File, startMs: Long, endMs: Long): List<String> {
        require(startMs >= 0 && startMs != Long.MAX_VALUE) { "Invalid audio range start" }
        require(endMs > startMs) { "Audio range must have a positive duration" }
        require(input.canonicalFile != output.canonicalFile) { "PCM output must not replace its source" }
        return buildList {
            addAll(listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-xerror", "-y"))
            // Applies to the input and all nested resources. A media file must not fetch a URL.
            addAll(listOf("-protocol_whitelist", "file", "-threads", "2"))
            addAll(listOf("-ss", seconds(startMs), "-accurate_seek", "-i", input.absolutePath))
            if (endMs != Long.MAX_VALUE) {
                addAll(listOf("-t", seconds(endMs - startMs)))
            } else {
                // A bad/missing duration must not fill storage. One extra byte makes truncation
                // detectable by read(..., MAX_EOF_SAMPLES); FFmpeg may finish its current packet.
                addAll(listOf("-fs", (MAX_EOF_SAMPLES.toLong() * 2 + 1).toString()))
            }
            addAll(listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-threads", "2",
                "-ac", "1", "-ar", SAMPLE_RATE.toString(), "-c:a", "pcm_s16le", "-f", "s16le",
                output.absolutePath))
        }
    }

    /**
     * Reads bounded mono s16le output as floats. Empty output is allowed for a confirmed EOF; callers
     * decide whether that is valid for their window. Refuse oversized or incomplete output before
     * allocation, including a file that changes while being read.
     */
    fun read(file: File, maxSamples: Int): FloatArray {
        require(maxSamples >= 0) { "PCM sample limit must not be negative" }
        val bytes = file.length()
        require(file.isFile && bytes % 2L == 0L) { "Invalid PCM output length: $bytes" }
        require(bytes / 2L <= maxSamples.toLong()) { "PCM output exceeds the window limit" }
        val samples = FloatArray((bytes / 2L).toInt())
        DataInputStream(file.inputStream().buffered()).use { input ->
            val buffer = ByteArray(8192)
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(buffer.size / 2, samples.size - offset)
                input.readFully(buffer, 0, count * 2)
                for (i in 0 until count) {
                    val value = (buffer[2 * i].toInt() and 0xff) or (buffer[2 * i + 1].toInt() shl 8)
                    samples[offset + i] = value.toShort() / 32768f
                }
                offset += count
            }
            check(input.read() == -1) { "PCM output changed while being read" }
        }
        return samples
    }

    /** Avoid locale-dependent decimal separators and floating-point rounding. */
    private fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
}
