package com.changyow.mediadler.core.sharing

import org.apache.hc.core5.http.ClassicHttpRequest
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.impl.bootstrap.HttpServer
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap
import org.apache.hc.core5.http.io.SocketConfig
import org.apache.hc.core5.http.io.entity.ByteArrayEntity
import org.apache.hc.core5.io.CloseMode
import org.apache.hc.core5.util.Timeout
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.util.logging.Level
import java.util.logging.Logger

/** Tiny, read-only browser UI over the same live folder exposed by SFTP. */
class WebShareServer(
    private val files: SharedFiles,
    private val address: InetAddress,
    private val expiresAtMillis: Long,
    private val nowMillis: () -> Long,
    private val port: Int = DEFAULT_HTTP_PORT,
) : Closeable {
    private var server: HttpServer? = null
    val httpPort: Int get() = server?.localPort ?: 0

    fun start() {
        check(server == null)
        val started = ServerBootstrap.bootstrap().setLocalAddress(address).setListenerPort(port)
            .setCanonicalHostName(address.hostAddress)
            .setSocketConfig(SocketConfig.custom().setSoReuseAddress(true).setSoTimeout(Timeout.ofSeconds(30)).build())
            .register("*") { request, response, _ -> handle(request, response) }.create()
        server = started
        try { started.start() }
        catch (failure: Throwable) { close(); throw failure }
    }

    private fun handle(request: ClassicHttpRequest, response: ClassicHttpResponse) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
        try {
            // Only the displayed numeric-IP URL is valid; a foreign DNS name must not expose files.
            val host = address.hostAddress.let { if (':' in it) "[$it]" else it }
            val authority = "$host:$httpPort"
            val hosts = request.getHeaders("Host")
            if (hosts.size != 1 || hosts.single().value != authority) { text(response, 403, "Access denied"); return }
            if (expired()) { text(response, 410, "分享已到期，請在手機重新開啟分享。"); return }
            val route = request.path.substringBefore('?')
            if (request.method != "GET" && request.method != "HEAD") {
                response.setHeader("Allow", "GET, HEAD")
                text(response, 405, "Read-only sharing")
                return
            }
            when {
                route == "/" -> listing("/", response)
                route.startsWith("/folder/") -> listing(sharedPath(route.removePrefix("/folder/")), response)
                route.startsWith("/download/") -> serveSharedFile(files, sharedPath(route.removePrefix("/download/")), request, response, download = true, checkReadable = ::checkReadable)
                else -> text(response, 404, "File or folder unavailable")
            }
        } catch (failure: Exception) {
            val code = when (failure) {
                is ShareExpiredException -> 410
                is NoSuchFileException, is NotDirectoryException -> 404
                is AccessDeniedException, is SecurityException -> 403
                is IllegalArgumentException -> 400
                else -> 500
            }
            if (code == 500) Logger.getLogger("WebShareServer").log(Level.WARNING, "Web share request failed", failure)
            text(response, code, "File or folder unavailable")
        }
    }

    private fun expired(): Boolean = nowMillis() >= expiresAtMillis

    private fun checkReadable() { if (expired()) throw ShareExpiredException() }

    private class ShareExpiredException : IOException("Sharing session expired")

    private fun sharedPath(id: String): String {
        require(id.isNotEmpty())
        val path = objectPath(id)
        if (path.split('/').any { it.isNotEmpty() && !SharePath.isPublished(it) }) throw NoSuchFileException(path)
        return path
    }

    private fun listing(path: String, response: ClassicHttpResponse) {
        checkReadable()
        if (!files.stat(path).directory) throw NotDirectoryException(path)
        val entries = files.list(path).sortedWith(compareBy<SharedEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        checkReadable()
        val html = buildString {
            append("<!doctype html><html lang=\"zh-Hant\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>media-dler 檔案分享</title>")
            append("<style>body{font:16px system-ui,sans-serif;max-width:800px;margin:24px auto;padding:0 16px;color:#222}h1{font-size:24px}p,li{overflow-wrap:anywhere}ul{padding:0;list-style:none}li{padding:14px 0;border-bottom:1px solid #ddd}a{color:#1760a5}small{color:#666;margin-left:12px}</style>")
            append("<h1>media-dler 檔案分享</h1><p>").append(escape(path)).append("</p>")
            if (path != "/") {
                val parent = path.substringBeforeLast('/').ifEmpty { "/" }
                append("<p><a href=\"").append(folderUrl(parent)).append("\">↑ 上一層</a></p>")
            }
            append("<ul>")
            for (entry in entries) {
                append("<li><a href=\"").append(if (entry.directory) folderUrl(entry.path) else "/download/${objectId(entry.path)}").append("\">")
                append(escape(entry.name)).append(if (entry.directory) "/" else "").append("</a>")
                if (!entry.directory) append("<small>下載 · ").append(entry.size).append(" bytes</small>")
                append("</li>")
            }
            append("</ul>")
            if (entries.isEmpty()) append("<p>此資料夾沒有可分享的檔案。</p>")
            append("</html>")
        }
        response.code = 200
        response.entity = ByteArrayEntity(html.toByteArray(Charsets.UTF_8), ContentType.create("text/html", Charsets.UTF_8))
    }

    private fun folderUrl(path: String): String = if (path == "/") "/" else "/folder/${objectId(path)}"

    private fun escape(text: String): String = buildString {
        for (character in text) append(when (character) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> character.toString()
        })
    }

    private fun text(response: ClassicHttpResponse, code: Int, message: String) {
        response.code = code
        response.entity = ByteArrayEntity(message.toByteArray(Charsets.UTF_8), ContentType.TEXT_PLAIN)
    }

    override fun close() { server?.close(CloseMode.IMMEDIATE); server = null }

    companion object {
        const val DEFAULT_HTTP_PORT = 8193
    }
}
