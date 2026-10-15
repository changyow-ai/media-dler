package com.changyow.mediadler.data.web

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SitePageClientTest {
    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    @Volatile private var activeSocket: Socket? = null
    private val serverFailures = ConcurrentLinkedQueue<Throwable>()
    private val requests = ConcurrentLinkedQueue<Request>()

    @After fun stopServer() {
        server?.close()
        activeSocket?.close()
        serverThread?.join(6_000)
        assertFalse(serverThread?.isAlive == true, "Fixture server did not stop")
        serverFailures.firstOrNull()?.let { throw AssertionError("Fixture server failed", it) }
    }

    @Test fun followsPermittedRelativeRedirectAndReadsUtf8WithRequestHeaders() = runBlocking {
        val html = "<html>劇集目錄：第十集</html>"
        val base = startServer { exchange ->
            when (exchange.path) {
                "/allowed/start" -> exchange.respond(302, location = "nested/final")
                "/allowed/nested/final" -> exchange.respond(200, html)
                else -> exchange.respond(404)
            }
        }
        val referer = "https://fixture.example/playback"
        val page = SitePageClient().read(
            "$base/allowed/start#ignored", "測試站", "fixture-agent", allowed(base), referer,
        )

        assertEquals("$base/allowed/nested/final", page.url)
        assertEquals(html, page.html)
        assertEquals(listOf("/allowed/start", "/allowed/nested/final"), requests.map { it.path })
        requests.forEach {
            assertEquals("GET", it.method)
            assertEquals("fixture-agent", it.userAgent)
            assertEquals(referer, it.referer)
            assertEquals("text/html,application/xhtml+xml", it.accept)
        }
    }

    @Test fun refusesDisallowedInitialUrlWithoutSendingRequest() = runBlocking {
        val base = startServer { it.respond(200, "must not be requested") }
        val error = assertFailsWith<IllegalStateException> {
            SitePageClient().read("$base/forbidden", "測試站", "fixture-agent", allowed(base))
        }
        assertTrue(error.message.orEmpty().contains("測試站"))
        assertTrue(requests.isEmpty())
    }

    @Test fun refusesRedirectBeforeRequestingDisallowedDestination() = runBlocking {
        val base = startServer { exchange ->
            if (exchange.path == "/allowed/start") {
                exchange.respond(302, location = "/forbidden")
            } else {
                exchange.respond(200, "must not be requested")
            }
        }
        val error = assertFailsWith<IllegalStateException> {
            SitePageClient().read("$base/allowed/start", "測試站", "fixture-agent", allowed(base))
        }
        assertTrue(error.message.orEmpty().contains("測試站"))
        assertTrue(error.message.orEmpty().contains("支援"))
        assertEquals(listOf("/allowed/start"), requests.map { it.path })
    }

    @Test fun reportsHttpErrorWithSiteAndStatus() = runBlocking {
        val base = startServer { it.respond(503, "Unavailable") }
        val error = assertFailsWith<IllegalStateException> {
            SitePageClient().read("$base/allowed/page", "測試站", "fixture-agent", allowed(base))
        }
        assertTrue(error.message.orEmpty().contains("測試站"))
        assertTrue(error.message.orEmpty().contains("HTTP 503"))
        assertEquals(1, requests.size)
    }

    @Test fun reportsRedirectWithoutLocation() = runBlocking {
        val base = startServer { it.respond(302) }
        val error = assertFailsWith<IllegalStateException> {
            SitePageClient().read("$base/allowed/start", "測試站", "fixture-agent", allowed(base))
        }
        assertTrue(error.message.orEmpty().contains("測試站"))
        assertTrue(error.message.orEmpty().contains("缺少目的地"))
        assertEquals(1, requests.size)
    }

    @Test fun stopsRedirectLoopWithoutRequestingVisitedPageAgain() = runBlocking {
        val base = startServer {
            val next = if (it.path == "/allowed/start") "/allowed/next" else "/allowed/start#again"
            it.respond(302, location = next)
        }
        val error = assertFailsWith<IllegalStateException> {
            SitePageClient().read("$base/allowed/start", "測試站", "fixture-agent", allowed(base))
        }
        assertTrue(error.message.orEmpty().contains("測試站"))
        assertTrue(error.message.orEmpty().contains("循環轉址"))
        assertEquals(listOf("/allowed/start", "/allowed/next"), requests.map { it.path })
    }

    @Test fun limitsRedirectChainToConfiguredRequestCount() = runBlocking {
        val base = startServer {
            val next = it.path.substringAfterLast('/').toInt() + 1
            it.respond(302, location = "/allowed/$next")
        }
        val error = assertFailsWith<IllegalStateException> {
            SitePageClient(maxRequests = 3).read("$base/allowed/1", "測試站", "fixture-agent", allowed(base))
        }
        assertTrue(error.message.orEmpty().contains("測試站"))
        assertTrue(error.message.orEmpty().contains("轉址過多"))
        assertEquals(listOf("/allowed/1", "/allowed/2", "/allowed/3"), requests.map { it.path })
    }

    @Test fun acceptsExactCharacterLimitAndRejectsLargerUtf8Page() = runBlocking {
        val base = startServer {
            it.respond(200, if (it.path.endsWith("exact")) "一二三四" else "一二三四五")
        }
        val client = SitePageClient(maxChars = 4)
        assertEquals("一二三四", client.read("$base/allowed/exact", "測試站", "fixture-agent", allowed(base)).html)
        val error = assertFailsWith<IllegalStateException> {
            client.read("$base/allowed/oversize", "測試站", "fixture-agent", allowed(base))
        }
        assertTrue(error.message.orEmpty().contains("測試站"))
        assertTrue(error.message.orEmpty().contains("資料過大"))
    }

    private fun startServer(handler: (Request) -> Response): String {
        val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        server = listener
        serverThread = Thread({
            try {
                while (!listener.isClosed) {
                    listener.accept().use { socket ->
                        activeSocket = socket
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val requestLine = requireNotNull(reader.readLine()).split(' ')
                        check(requestLine.size == 3) { "Malformed fixture request line" }
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = requireNotNull(reader.readLine())
                            if (line.isEmpty()) break
                            val separator = line.indexOf(':')
                            check(separator > 0) { "Malformed fixture request header" }
                            headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                        }
                        val request = Request(
                            URI(requestLine[1]).path, requestLine[0],
                            headers["user-agent"], headers["referer"], headers["accept"],
                        )
                        requests += request
                        val response = handler(request)
                        val body = response.body.toByteArray(Charsets.UTF_8)
                        val responseHeaders = buildString {
                            append("HTTP/1.1 ${response.status} Fixture response\r\n")
                            append("Content-Type: text/html; charset=UTF-8\r\n")
                            append("Content-Length: ${body.size}\r\n")
                            append("Connection: close\r\n")
                            response.location?.let { append("Location: $it\r\n") }
                            append("\r\n")
                        }
                        socket.getOutputStream().apply {
                            write(responseHeaders.toByteArray(Charsets.US_ASCII))
                            write(body)
                            flush()
                        }
                        activeSocket = null
                    }
                }
            } catch (error: Exception) {
                if (!listener.isClosed) serverFailures += error
            } finally {
                activeSocket = null
            }
        }, "site-page-client-fixture").apply {
            isDaemon = true
            start()
        }
        return "http://127.0.0.1:${listener.localPort}"
    }

    private fun allowed(base: String): (String) -> Boolean = { url ->
        val uri = URI(url)
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == URI(base).port &&
            uri.path.startsWith("/allowed/")
    }

    private fun Request.respond(status: Int, body: String = "response", location: String? = null) =
        Response(status, body, location)

    private data class Response(val status: Int, val body: String, val location: String?)

    private data class Request(
        val path: String,
        val method: String,
        val userAgent: String?,
        val referer: String?,
        val accept: String?,
    )
}
