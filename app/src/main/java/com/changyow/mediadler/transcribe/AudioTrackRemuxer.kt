package com.changyow.mediadler.transcribe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies a media file's first audio track into a standalone `.m4a` (MPEG-4) without re-encoding —
 * a lossless container remux via [MediaExtractor]/[MediaMuxer]. Used by [AudioExtractionService] for
 * the "取出聲音" share option: it preserves the source's original codec, sample rate and channels
 * (unlike [AudioToPcm], which downmixes to 16 kHz mono for whisper), runs fast, and holds only one
 * sample buffer in memory regardless of length.
 */
object AudioTrackRemuxer {

    private const val DEFAULT_BUFFER = 256 * 1024

    /** Remuxes the first audio track of [uri] into [out] (overwritten). Throws if there is none. */
    suspend fun remuxToM4a(context: Context, uri: Uri, out: File) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            // setDataSource(context, uri) fails ("Failed to instantiate extractor") for granted
            // content:// uris in a service; open the fd via ContentResolver (which honours the read
            // grant, same as openInputStream) and feed that instead. Works for file:// uris too.
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                requireNotNull(pfd) { "無法開啟檔案" }
                extractor.setDataSource(pfd.fileDescriptor)
            }
            val track = audioTrack(extractor) ?: error("這個檔案沒有音軌")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)

            val bufferSize = format.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }
                ?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                ?.takeIf { it > 0 } ?: DEFAULT_BUFFER

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()

            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    /** First track whose MIME starts with "audio/" (mirrors AudioToPcm's track selection). */
    private fun audioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
}
