package com.changyow.mediadler.sharing

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.StorageMode
import com.changyow.mediadler.core.sharing.*
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

/** Live directory reads, independent of download history. No copies and no media database. */
class AndroidSharedFiles(private val context: Context, settings: AppSettings) : SharedFiles {
    private val saf = settings.storageMode == StorageMode.SAF
    private val tree = settings.safTreeUri?.let(Uri::parse)
    private val resolver = context.contentResolver

    private data class Located(val entry: SharedEntry, val uri: Uri?)

    override fun stat(path: String): SharedEntry = actualMetadata(locate(SharePath.normalize(path)))

    private fun actualMetadata(found: Located): SharedEntry {
        val uri = found.uri ?: return found.entry
        return resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val size = descriptor.statSize
            if (size < 0) throw IOException("此儲存位置無法提供檔案大小，請選擇本機資料夾")
            found.entry.copy(size = size)
        } ?: throw NoSuchFileException(found.entry.path)
    }
    override fun list(path: String): List<SharedEntry> {
        val normalized = SharePath.normalize(path)
        return if (saf) {
            val directory = document(normalized)
            if (!directory.isDirectory) throw NotDirectoryException(normalized)
            directory.listFiles().mapNotNull { doc ->
                val name = doc.name ?: return@mapNotNull null
                if (!SharePath.isPublished(name) || !doc.canRead()) return@mapNotNull null
                entry(doc, SharePath.child(normalized, name))
            }
        } else {
            val all = downloads()
            if (normalized != "/" && all.none { it.entry.path.startsWith("$normalized/") }) throw NotDirectoryException(normalized)
            val children = linkedMapOf<String, SharedEntry>()
            for (located in all) {
                val relative = located.entry.path.removePrefix(normalized.trimEnd('/') + "/")
                if (relative == located.entry.path || relative.isEmpty()) continue
                val name = relative.substringBefore('/')
                val child = SharePath.child(normalized, name)
                if ('/' in relative) children[child] = SharedEntry(child, name, true)
                else children[child] = if (located.entry.size == 0L) actualMetadata(located) else located.entry
            }
            children.values.toList()
        }.sortedWith(compareBy<SharedEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    override fun open(path: String): SeekableByteChannel {
        val found = locate(SharePath.normalize(path))
        if (found.entry.directory) throw AccessDeniedException(path)
        val descriptor = resolver.openFileDescriptor(requireNotNull(found.uri), "r") ?: throw NoSuchFileException(path)
        val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        return try {
            stream.channel.apply { position(0) } // Reject pipe-only/cloud providers instead of silently copying a movie.
        } catch (failure: Exception) {
            stream.close()
            throw IOException("此儲存位置不支援隨機讀取，請選擇本機資料夾", failure)
        }
    }

    private fun locate(path: String): Located {
        if (saf) {
            val doc = document(path)
            return Located(entry(doc, path), if (doc.isDirectory) null else doc.uri)
        }
        if (path == "/") return Located(SharedEntry("/", "media-dler", true), null)
        val all = downloads()
        all.firstOrNull { it.entry.path == path }?.let { return it }
        if (all.any { it.entry.path.startsWith("$path/") })
            return Located(SharedEntry(path, path.substringAfterLast('/'), true), null)
        throw NoSuchFileException(path)
    }

    private fun document(path: String): DocumentFile {
        var doc = tree?.let { DocumentFile.fromTreeUri(context, it) } ?: throw IOException("請先選擇下載資料夾")
        if (!doc.canRead()) throw AccessDeniedException("下載資料夾權限已失效，請重新選擇")
        for (name in path.split('/').filter { it.isNotEmpty() }) {
            if (!SharePath.isPublished(name)) throw NoSuchFileException(path)
            doc = doc.findFile(name) ?: throw NoSuchFileException(path)
        }
        if (!doc.canRead()) throw AccessDeniedException(path)
        return doc
    }

    private fun entry(doc: DocumentFile, path: String) = SharedEntry(
        path, if (path == "/") doc.name ?: "media-dler" else requireNotNull(doc.name), doc.isDirectory,
        if (doc.isFile) doc.length() else 0, doc.lastModified(), doc.type ?: "application/octet-stream",
    )

    private fun downloads(): List<Located> {
        check(Build.VERSION.SDK_INT >= 29) { "Android 9 請選擇下載資料夾" }
        val base = "Download/media-dler/"
        val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED)
        val result = mutableListOf<Located>()
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, columns,
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING}=0",
            arrayOf("$base%"), "${MediaStore.Downloads._ID} ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val relative = cursor.getString(2) ?: continue
                if (!relative.startsWith(base) || !SharePath.isPublished(name)) continue
                val parts = relative.removePrefix(base).split('/').filter { it.isNotEmpty() }
                if (parts.any { !SharePath.isPublished(it) || it == "." || it == ".." }) continue
                val path = SharePath.child("/" + parts.joinToString("/"), name)
                result += Located(SharedEntry(path, name, false, cursor.getLong(4), cursor.getLong(5) * 1000,
                    cursor.getString(3) ?: "application/octet-stream"),
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
            }
        } ?: throw IOException("無法讀取下載資料夾")
        return result
    }
}
