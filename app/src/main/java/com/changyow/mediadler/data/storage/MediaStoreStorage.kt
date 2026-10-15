package com.changyow.mediadler.data.storage

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.changyow.mediadler.core.repo.DownloadOutput
import java.io.File
import java.io.FileOutputStream

/** Saves files into the public Download/media-dler/ collection via MediaStore (scoped-storage friendly). */
class MediaStoreStorage(private val context: Context) {

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun publicationTarget(): PublicationTarget = object : PublicationTarget {
        override fun discard(artifact: CompletedArtifact): Boolean {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val staging = "media-dler-${artifact.token}.partial.${artifact.displayName.substringAfterLast('.', "bin")}"
            val owned = mutableListOf<Uri>()
            resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.IS_PENDING}=1",
                arrayOf(relativePath(artifact), staging), null)?.use {
                while (it.moveToNext()) owned += ContentUris.withAppendedId(collection, it.getLong(0))
            } ?: return false
            return owned.all { resolver.delete(it, null, null) == 1 }
        }

        override suspend fun findOrCreate(artifact: CompletedArtifact): String {
            val relative = relativePath(artifact)
            val staging = "media-dler-${artifact.token}.partial.${artifact.displayName.substringAfterLast('.', "bin")}"
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(relative, staging), null)?.use { cursor ->
                if (cursor.moveToFirst()) return ContentUris.withAppendedId(collection, cursor.getLong(0)).toString()
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, staging)
                put(MediaStore.Downloads.MIME_TYPE, artifact.mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            return (resolver.insert(collection, values) ?: error("無法在 Downloads 建立暫存檔案")).toString()
        }

        override suspend fun copy(artifact: CompletedArtifact, uri: String, source: File) {
            val resolver = context.contentResolver
            resolver.query(Uri.parse(uri), arrayOf(MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING), null, null, null)?.use {
                check(it.moveToFirst() && it.getString(0).startsWith("media-dler-${artifact.token}.partial.") &&
                    it.getString(1).trimEnd('/') == relativePath(artifact).trimEnd('/') && it.getInt(2) == 1) {
                    "Downloads 暫存檔案識別不符，拒絕覆寫"
                }
            } ?: error("無法讀取 Downloads 暫存檔案")
            // An interrupted copy stays pending and is truncated/reused on retry.
            resolver.openFileDescriptor(Uri.parse(uri), "rwt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            } ?: error("無法寫入 Downloads 暫存檔案")
        }

        override suspend fun finish(artifact: CompletedArtifact, uri: String): DownloadOutput {
            val resolver = context.contentResolver
            val parsed = Uri.parse(uri)
            // Pending MediaStore metadata may not have been scanned yet; stat the
            // actual file instead of treating a stale SIZE column as a failed copy.
            resolver.openFileDescriptor(parsed, "r")?.use {
                check(it.statSize == artifact.size) { "Downloads 輸出檔案尚未完整寫入" }
            } ?: error("無法讀取 Downloads 輸出檔案")
            var currentName: String? = null
            var pending = true
            resolver.query(parsed, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.IS_PENDING,
                MediaStore.Downloads.RELATIVE_PATH), null, null, null)?.use { cursor ->
                check(cursor.moveToFirst()) { "Downloads 輸出檔案已不存在" }
                currentName = cursor.getString(0)
                pending = cursor.getInt(1) != 0
                check(cursor.getString(2).trimEnd('/') == relativePath(artifact).trimEnd('/')) {
                    "Downloads 輸出檔案位置已變更"
                }
                if (pending) check(requireNotNull(currentName).startsWith("media-dler-${artifact.token}.partial.")) {
                    "Downloads 暫存檔案識別不符"
                }
            }
            check(currentName != null) { "無法讀取 Downloads 輸出檔案" }
            if (pending) {
                val used = mutableSetOf<String>()
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                    "${MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf(relativePath(artifact)), null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                cursor.getLong(0)) != parsed) used += cursor.getString(1)
                    }
                }
                val name = availableName(artifact.displayName, used)
                check(resolver.update(parsed, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null) == 1) { "無法發布 Downloads 輸出檔案" }
                resolver.query(parsed, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    check(it.moveToFirst())
                    currentName = it.getString(0)
                }
            }
            return DownloadOutput(uri, requireNotNull(currentName), artifact.mimeType)
        }
    }

    private fun relativePath(artifact: CompletedArtifact): String =
        (listOf(Environment.DIRECTORY_DOWNLOADS, "media-dler") + artifact.folders).joinToString("/", postfix = "/")

    private fun availableName(name: String, used: Set<String>): String {
        if (name !in used) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        for (index in 1..10000) {
            val candidate = "$base ($index)$suffix"
            if (candidate !in used) return candidate
        }
        error("同名檔案過多，無法建立輸出檔案")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun save(source: File, displayName: String, mimeType: String): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/media-dler")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("無法在 Downloads 建立檔案")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { it.copyTo(output) }
                } ?: error("無法寫入檔案")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            uri
        }
}
