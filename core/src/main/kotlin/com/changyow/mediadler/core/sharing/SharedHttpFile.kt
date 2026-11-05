package com.changyow.mediadler.core.sharing

import org.apache.hc.core5.http.ClassicHttpRequest
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.BasicHttpEntity
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.channels.Channels

/** The same streaming/seek handling serves DLNA playback and browser downloads. */
internal fun serveSharedFile(
    files: SharedFiles,
    path: String,
    request: ClassicHttpRequest,
    response: ClassicHttpResponse,
    download: Boolean = false,
    checkReadable: () -> Unit = {},
) {
    checkReadable()
    val entry = files.stat(path)
    if (entry.directory || entry.size < 0) { response.code = 404; return }
    val etag = "\"${entry.size.toString(16)}-${entry.modifiedMillis.toString(16)}\""
    val raw = request.getFirstHeader("Range")?.value?.takeIf {
        request.getFirstHeader("If-Range")?.value.let { tag -> tag == null || tag == etag }
    }
    val range = ByteRange.parse(raw, entry.size)
    if (range == null) {
        response.code = 416
        response.setHeader("Content-Range", "bytes */${entry.size}")
        return
    }
    response.code = if (range.partial) 206 else 200
    response.setHeader("Accept-Ranges", "bytes")
    response.setHeader("ETag", etag)
    if (download) {
        val fallback = entry.name.map { if (it in ' '..'~' && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = URLEncoder.encode(entry.name, "UTF-8").replace("+", "%20")
        response.setHeader("Content-Disposition", "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded")
    }
    if (range.partial) response.setHeader("Content-Range", "bytes ${range.start}-${range.end}/${entry.size}")
    val stream = if (request.method == "HEAD") ByteArrayInputStream(byteArrayOf()) else {
        checkReadable()
        val channel = files.open(path)
        try {
            channel.position(range.start)
            LimitedInputStream(Channels.newInputStream(channel), range.length, checkReadable)
        } catch (failure: Throwable) { channel.close(); throw failure }
    }
    response.entity = BasicHttpEntity(stream, range.length,
        if (download) ContentType.APPLICATION_OCTET_STREAM else ContentType.parse(entry.mimeType))
}

private class LimitedInputStream(private val source: InputStream, private var remaining: Long, private val checkReadable: () -> Unit) : InputStream() {
    override fun read(): Int {
        checkReadable()
        if (remaining == 0L) return -1
        return source.read().also { if (it >= 0) remaining-- }
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        checkReadable()
        if (length == 0) return 0
        if (remaining == 0L) return -1
        return source.read(bytes, offset, minOf(length.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
    }
    override fun close() = source.close()
}
