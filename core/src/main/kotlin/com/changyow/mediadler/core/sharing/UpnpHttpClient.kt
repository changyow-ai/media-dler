package com.changyow.mediadler.core.sharing

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jupnp.model.message.*
import org.jupnp.transport.spi.StreamClient
import org.jupnp.transport.spi.StreamClientConfiguration
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** UPnP callbacks accept local addresses only, with no proxy credentials or redirects. */
internal class UpnpHttpClient(private val bindAddress: InetAddress) : StreamClient<StreamClientConfiguration> {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> = InetAddress.getAllByName(hostname).filter {
                it.isSiteLocalAddress || it.isLinkLocalAddress || bindAddress.isLoopbackAddress && it.isLoopbackAddress
            }.also { require(it.isNotEmpty()) { "UPnP callback must be local" } }
        }).build()
    private val config = object : StreamClientConfiguration {
        override fun getRequestExecutorService() = client.dispatcher.executorService
        override fun getTimeoutSeconds() = 10
        override fun getLogWarningSeconds() = 5
        override fun getRetryAfterSeconds() = 1
        override fun getRetryIterations() = 0
        override fun getUserAgentValue(major: Int, minor: Int) = "Android UPnP/1.0 media-dler/1.0"
    }
    override fun getConfiguration() = config
    override fun sendRequest(message: StreamRequestMessage): StreamResponseMessage? = try {
        require(InetAddress.getAllByName(message.uri.host).all {
            it.isSiteLocalAddress || it.isLinkLocalAddress || bindAddress.isLoopbackAddress && it.isLoopbackAddress
        })
        val method = message.operation.httpMethodName
        val body = if (message.hasBody()) message.bodyBytes.toRequestBody() else if (method == "POST") byteArrayOf().toRequestBody() else null
        val request = Request.Builder().url(message.uri.toURL()).method(method, body)
        message.headers.forEach { (key, values) -> values.forEach { request.addHeader(key, it) } }
        if (!message.headers.containsKey("User-Agent")) request.header("User-Agent", config.getUserAgentValue(1, 0))
        client.newCall(request.build()).execute().use { response ->
            StreamResponseMessage(UpnpResponse(response.code, response.message)).apply {
                response.headers.forEach { (key, value) -> headers.add(key, value) }
                response.body?.byteStream()?.use {
                    val bytes = it.readBounded(65537)
                    require(bytes.size <= 65536)
                    if (bytes.isNotEmpty()) setBodyCharacters(bytes)
                }
            }
        }
    } catch (_: Exception) { null }
    override fun stop() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
