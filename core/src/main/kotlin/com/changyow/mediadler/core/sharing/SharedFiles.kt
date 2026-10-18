package com.changyow.mediadler.core.sharing

import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException

data class SharedEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long = 0,
    val modifiedMillis: Long = 0,
    val mimeType: String = "application/octet-stream",
)

/** Paths are virtual, rooted at the selected download folder; never Android filesystem paths. */
interface SharedFiles {
    fun stat(path: String): SharedEntry
    fun list(path: String): List<SharedEntry>
    fun open(path: String): SeekableByteChannel
}

object SharePath {
    fun normalize(path: String): String {
        if (path.length > 4096 || '\u0000' in path) throw AccessDeniedException(path)
        val parts = mutableListOf<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) throw AccessDeniedException(path) else parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
        return "/" + parts.joinToString("/")
    }

    fun child(parent: String, name: String): String {
        if (name.isBlank() || '/' in name || name == "." || name == "..") throw AccessDeniedException(name)
        return normalize("${normalize(parent).trimEnd('/')}/$name")
    }

    fun isPublished(name: String): Boolean = !name.startsWith('.') &&
        !(name.startsWith("media-dler-") && name.contains(".partial.")) &&
        !name.lowercase().let { it.endsWith(".part") || it.endsWith(".tmp") || it.endsWith(".ytdl") }
}
