package com.changyow.mediadler.core.download

import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.SourceKind

/** Directory names use confirmed metadata only; source identity never comes from a CDN URL. */
object DownloadDestinationPolicy {
    data class Destination(val folders: List<String>, val displayName: String)

    fun destination(item: MediaItem, extension: String): Destination {
        val special = item.source.kind == SourceKind.GIMY || item.source.kind == SourceKind.EIGHT_MAPLE
        val folders = if (!special) emptyList() else buildList {
            add("drama")
            item.seriesTitle?.takeIf { it.isNotBlank() }?.let { add(sanitize(it)) }
            item.seasonNumber?.takeIf { it > 0 }?.let { add("Season ${it.toString().padStart(2, '0')}") }
        }
        val prefix = if (special) item.episodeNumber?.takeIf { it > 0 }
            ?.let { "E${it.toString().padStart(4, '0')} " }.orEmpty() else ""
        val name = sanitize(prefix + item.title)
        val ext = extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "bin"
        return Destination(folders, "$name.$ext")
    }

    fun sanitize(value: String): String {
        val cleaned = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        // Most filesystems cap a component at 255 UTF-8 bytes. Leave room for
        // extension, collision number and SAF recovery token, including Chinese titles.
        var bytes = 0
        val result = StringBuilder()
        for (point in cleaned.codePoints().toArray()) {
            val text = String(Character.toChars(point))
            val size = text.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 180 || result.length + text.length > 120) break
            result.append(text)
            bytes += size
        }
        return result.toString().trim().ifBlank { "media" }
    }
}
