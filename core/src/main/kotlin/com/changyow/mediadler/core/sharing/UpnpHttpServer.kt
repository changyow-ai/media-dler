package com.changyow.mediadler.core.sharing

import org.apache.hc.core5.http.*
import org.apache.hc.core5.http.impl.Http1StreamListener
import org.apache.hc.core5.http.impl.bootstrap.HttpServer
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap
import org.apache.hc.core5.http.io.SocketConfig
import org.apache.hc.core5.http.io.entity.BasicHttpEntity
import org.apache.hc.core5.http.io.entity.ByteArrayEntity
import org.apache.hc.core5.http.protocol.HttpCoreContext
import org.apache.hc.core5.io.CloseMode
import org.apache.hc.core5.util.Timeout
import org.jupnp.model.message.*
import org.jupnp.transport.Router
import org.jupnp.transport.spi.StreamServer
import org.jupnp.transport.spi.StreamServerConfiguration
import org.jupnp.transport.spi.UpnpStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.nio.channels.Channels
import java.util.Collections
import java.util.WeakHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** HTTP framing stays in HttpCore; jUPnP owns SOAP, descriptors, subscriptions and SSDP. */
internal class UpnpHttpServer(private val files: SharedFiles) : StreamServer<StreamServerConfiguration> {
    private lateinit var server: HttpServer
    private val deliveries = Collections.synchronizedMap(WeakHashMap<Any, Delivery>())
    private val config = object : StreamServerConfiguration {
        override fun getListenPort() = 0
    }
    override fun getConfiguration() = config
    override fun getPort(): Int = if (::server.isInitialized) server.localPort else 0
    override fun run() = Unit // HttpCore owns its accept loop.
    override fun stop() {
        if (::server.isInitialized) server.close(CloseMode.IMMEDIATE)
        deliveries.clear()
    }
    override fun init(bindAddress: InetAddress, router: Router) {
        server = ServerBootstrap.bootstrap().setLocalAddress(bindAddress).setListenerPort(0)
            .setCanonicalHostName(bindAddress.hostAddress)
            .setSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofSeconds(30)).build())
            .setStreamListener(object : Http1StreamListener {
                override fun onRequestHead(connection: HttpConnection, request: HttpRequest) = Unit
                override fun onResponseHead(connection: HttpConnection, response: HttpResponse) {
                    deliveries.remove(response)?.let { deliveries[connection] = it }
                }
                override fun onExchangeComplete(connection: HttpConnection, keepAlive: Boolean) {
                    deliveries.remove(connection)?.sent()
                }
            })
            .register("*") { request, response, context ->
                try {
                    if (request.path.startsWith("/media/")) media(request, response)
                    else {
                        val length = request.entity?.contentLength ?: 0
                        require(length <= MAX_CONTROL_BODY) { "UPnP request too large" }
                        val body = request.entity?.content?.use { input ->
                            val bytes = input.readBounded(MAX_CONTROL_BODY + 1)
                            require(bytes.size <= MAX_CONTROL_BODY)
                            bytes
                        } ?: byteArrayOf()
                        val method = UpnpRequest.Method.getByHttpName(request.method)
                        val message = StreamRequestMessage(method, URI(request.path))
                        request.headers.forEach { message.headers.add(it.name, it.value) }
                        message.connection = object : Connection {
                            override fun isOpen() = true
                            override fun getLocalAddress() = bindAddress
                            override fun getRemoteAddress(): InetAddress =
                                (HttpCoreContext.adapt(context).endpointDetails.remoteAddress as java.net.InetSocketAddress).address
                        }
                        if (body.isNotEmpty()) {
                            message.setBodyCharacters(body)
                            // UPnP SOAP does not need DTDs; reject them before the library's XML parser.
                            require(!message.bodyString.contains("<!DOCTYPE", ignoreCase = true) &&
                                !message.bodyString.contains("<!ENTITY", ignoreCase = true))
                        }
                        val delivery = Delivery(router)
                        val result = delivery.process(message)
                        response.code = result?.operation?.statusCode ?: 404
                        if (result != null) {
                            result.headers.forEach { (key, values) ->
                                if (!key.equals("Content-Length", true) && !key.equals("Transfer-Encoding", true))
                                    values.forEach { response.addHeader(key, it) }
                            }
                            response.entity = ByteArrayEntity(if (result.hasBody()) result.bodyBytes else byteArrayOf(),
                                ContentType.parse(result.headers.getFirstHeaderString(org.jupnp.model.message.header.UpnpHeader.Type.CONTENT_TYPE) ?: "text/xml; charset=utf-8"))
                            delivery.result = result
                            deliveries[response] = delivery
                        }
                    }
                } catch (failure: Exception) {
                    response.code = when (failure) {
                        is java.nio.file.NoSuchFileException -> 404
                        is java.nio.file.AccessDeniedException, is SecurityException -> 403
                        is IllegalArgumentException -> 400
                        else -> 500
                    }
                    if (response.code == 500) Logger.getLogger("UpnpHttpServer").log(Level.WARNING, "UPnP request failed", failure)
                    response.entity = ByteArrayEntity("File or request unavailable".toByteArray(), ContentType.TEXT_PLAIN)
                }
            }.create()
        server.start()
    }

    private fun media(request: ClassicHttpRequest, response: ClassicHttpResponse) {
        if (request.method !in listOf("GET", "HEAD")) { response.code = 405; return }
        val path = objectPath(request.path.substringAfter("/media/").substringBefore('?'))
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
        response.setHeader("transferMode.dlna.org", "Streaming")
        if (range.partial) response.setHeader("Content-Range", "bytes ${range.start}-${range.end}/${entry.size}")
        val stream = if (request.method == "HEAD") ByteArrayInputStream(byteArrayOf()) else {
            val channel = files.open(path)
            try {
                channel.position(range.start)
                LimitedInputStream(Channels.newInputStream(channel), range.length)
            } catch (failure: Throwable) { channel.close(); throw failure }
        }
        response.entity = BasicHttpEntity(stream, range.length, ContentType.parse(entry.mimeType))
    }

    private class Delivery(router: Router) : UpnpStream(router.protocolFactory) {
        var result: StreamResponseMessage? = null
        override fun run() = Unit
        fun sent() { result?.let { responseSent(it) } }
    }
    private companion object { const val MAX_CONTROL_BODY = 65536 }
}

internal fun InputStream.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal data class ByteRange(val start: Long, val end: Long, val partial: Boolean) {
    val length: Long get() = if (end < start) 0 else end - start + 1
    companion object {
        fun parse(header: String?, size: Long): ByteRange? {
            require(size >= 0)
            // RFC 9110 permits ignoring unsupported units or multiple ranges.
            if (header == null || !header.startsWith("bytes=") || ',' in header) return ByteRange(0, size - 1, false)
            if (size == 0L) return null
            val parts = header.removePrefix("bytes=").trim().split('-')
            if (parts.size != 2) return null
            if (parts[0].isEmpty()) {
                val suffix = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
                return ByteRange((size - suffix.coerceAtMost(size)), size - 1, true)
            }
            val start = parts[0].toLongOrNull()?.takeIf { it in 0 until size } ?: return null
            val end = if (parts[1].isEmpty()) size - 1 else parts[1].toLongOrNull()?.coerceAtMost(size - 1) ?: return null
            return if (end < start) null else ByteRange(start, end, true)
        }
    }
}

private class LimitedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        return source.read().also { if (it >= 0) remaining-- }
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        return source.read(bytes, offset, length.toLong().coerceAtMost(remaining).toInt()).also { if (it > 0) remaining -= it }
    }
    override fun close() = source.close()
}
