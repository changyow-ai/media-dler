package com.changyow.mediadler.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Saves files into a user-picked directory via the Storage Access Framework. */
class SafStorage(private val context: Context) {

    suspend fun save(source: File, displayName: String, mimeType: String, treeUri: String?): Uri =
        withContext(Dispatchers.IO) {
            val tree = treeUri?.let(Uri::parse) ?: error("尚未選擇 SAF 目錄")
            val dir = DocumentFile.fromTreeUri(context, tree) ?: error("SAF 目錄無效")
            dir.findFile(displayName)?.delete()
            val doc = dir.createFile(mimeType, displayName) ?: error("無法在所選目錄建立檔案")
            context.contentResolver.openOutputStream(doc.uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: error("無法寫入所選目錄")
            doc.uri
        }
}
