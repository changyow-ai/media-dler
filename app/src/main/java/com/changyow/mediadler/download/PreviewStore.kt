package com.changyow.mediadler.download

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.changyow.mediadler.util.FileNames
import java.io.File
import java.io.FileOutputStream

/**
 * Generates and stores local video preview frames (app-private), so sources
 * without a usable remote thumbnail (Threads, Bilibili) still show one in the list.
 * Previews are deleted together with their task.
 */
class PreviewStore(private val context: Context) {

    private val dir = File(context.filesDir, "media-dler/preview")

    /** Extracts a frame from [videoUri], saves it as `<displayName>.jpg`; returns its path or null. */
    fun generate(videoUri: Uri, displayName: String): String? = try {
        dir.mkdirs()
        val base = FileNames.sanitize(displayName.substringBeforeLast('.', displayName))
        val out = File(dir, "$base.jpg")
        val retriever = MediaMetadataRetriever()
        val bitmap = try {
            retriever.setDataSource(context, videoUri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } finally {
            retriever.release()
        }
        if (bitmap == null) {
            null
        } else {
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
            out.absolutePath
        }
    } catch (t: Throwable) {
        null
    }

    fun delete(previewPath: String?) {
        if (previewPath.isNullOrBlank()) return
        runCatching { File(previewPath).delete() }
    }
}
