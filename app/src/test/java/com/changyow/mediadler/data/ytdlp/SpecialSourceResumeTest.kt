package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.model.MediaResource
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class SpecialSourceResumeTest {
    private val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"
    private val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild.m3u8\n"
    private val server = LocalHttpServer()

    @After fun closeServer() = server.close()

    @Test fun `single variant master has a stable retry identity and pins child bytes`() = runBlocking {
        server.reply("/master.m3u8", master)
        server.reply("/child.m3u8", vod)
        val resource = MediaResource(server.url("/master.m3u8"), mapOf("Referer" to "https://source.example/"))
        val first = assertNotNull(SpecialSourceResume.prepare(resource))
        val retry = assertNotNull(SpecialSourceResume.prepare(resource))
        assertEquals(first.identity, retry.identity)
        assertEquals(resource.copy(url = server.url("/child.m3u8")), first.resource)
        assertEquals(vod, first.manifest)
        assertEquals("https://source.example/", server.requests.last().headers["referer"])
    }

    @Test fun `direct media and redirected master child use their final response URLs`() = runBlocking {
        server.reply("/direct.m3u8", vod)
        val direct = assertNotNull(SpecialSourceResume.prepare(MediaResource(server.url("/direct.m3u8"))))
        assertEquals(server.url("/direct.m3u8"), direct.resource.url)
        server.redirect("/start", "/folder/master.m3u8")
        server.reply("/folder/master.m3u8", master)
        server.redirect("/folder/child.m3u8", "/effective/media.m3u8")
        server.reply("/effective/media.m3u8", vod)
        val redirected = assertNotNull(SpecialSourceResume.prepare(MediaResource(server.url("/start"))))
        assertEquals(server.url("/effective/media.m3u8"), redirected.resource.url)
        assertEquals(vod, redirected.manifest)
    }

    @Test fun `changed child bytes URL and header values invalidate identity`() = runBlocking {
        server.reply("/master.m3u8", master)
        server.reply("/child.m3u8", vod)
        server.reply("/other.m3u8", vod)
        val resource = MediaResource(server.url("/master.m3u8"), mapOf("Referer" to "one"))
        val original = assertNotNull(SpecialSourceResume.prepare(resource)).identity
        server.reply("/child.m3u8", vod.replace("a.ts", "b.ts"))
        assertNotEquals(original, assertNotNull(SpecialSourceResume.prepare(resource)).identity)
        server.reply("/child.m3u8", vod)
        assertNotEquals(original, assertNotNull(SpecialSourceResume.prepare(resource.copy(httpHeaders = mapOf("Referer" to "two")))).identity)
        server.reply("/master.m3u8", master.replace("child.m3u8", "other.m3u8"))
        assertNotEquals(original, assertNotNull(SpecialSourceResume.prepare(resource)).identity)
    }

    @Test fun `header case and ordering do not change identity but literal values do`() = runBlocking {
        server.reply("/media", vod)
        val resource = MediaResource(server.url("/media"), linkedMapOf("Referer" to "one", "X-Token" to "two"))
        val original = assertNotNull(SpecialSourceResume.prepare(resource)).identity
        val reordered = resource.copy(httpHeaders = linkedMapOf("x-token" to "two", "referer" to "one"))
        assertEquals(original, assertNotNull(SpecialSourceResume.prepare(reordered)).identity)
        assertNotEquals(original, assertNotNull(SpecialSourceResume.prepare(reordered.copy(httpHeaders = mapOf("referer" to "ONE", "x-token" to "two")))).identity)
    }

    @Test fun `ambiguous master nested master encrypted live and unreadable playlists are unsupported`() = runBlocking {
        server.reply("/child.m3u8", vod)
        listOf(
            master + "#EXT-X-STREAM-INF:BANDWIDTH=2000\nother.m3u8\n",
            master.replace("#EXT-X-STREAM-INF", "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF"),
            vod.replace("a.ts", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\na.ts"),
            vod.replace("#EXT-X-ENDLIST\n", ""), "<html>error</html>"
        ).forEach { manifest ->
            server.reply("/unsupported", manifest)
            assertNull(SpecialSourceResume.prepare(MediaResource(server.url("/unsupported"))))
        }
        server.reply("/nested", master)
        server.reply("/child.m3u8", master)
        assertNull(SpecialSourceResume.prepare(MediaResource(server.url("/nested"))))
        assertNull(SpecialSourceResume.prepare(MediaResource(server.url("/missing"))))
    }

    @Test fun `oversized manifests redirects beyond bound and URLs with user info are unsupported`() = runBlocking {
        server.reply("/large", vod + "#".repeat(1024 * 1024))
        assertNull(SpecialSourceResume.prepare(MediaResource(server.url("/large"))))
        (0..4).forEach { server.redirect("/r$it", "/r${it + 1}") }
        server.reply("/r5", vod)
        assertNull(SpecialSourceResume.prepare(MediaResource(server.url("/r0"))))
        assertNull(SpecialSourceResume.prepare(MediaResource(server.url("/r0").replace("http://", "http://user:password@"))))
        assertNull(SpecialSourceResume.prepare(MediaResource("file:///local.m3u8")))
    }

    @Test fun `cancellation during fetch propagates`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.routes["/waiting"] = {
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            Response(body = vod)
        }
        val prepared = async(Dispatchers.IO) { SpecialSourceResume.prepare(MediaResource(server.url("/waiting"))) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            prepared.cancel()
            release.countDown()
            assertFailsWith<CancellationException> { prepared.await() }
        } finally { release.countDown() }
        Unit
    }

    /** Android's unit-test compiler omits jdk.httpserver, so use a tiny loopback-only HTTP fixture. */
    private class LocalHttpServer {
        val routes = ConcurrentHashMap<String, (Request) -> Response>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        private val worker = thread(isDaemon = true) {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val request = Request(path, headers)
                    requests += request
                    val response = routes[path]?.invoke(request) ?: Response(404)
                    val bytes = response.body.toByteArray()
                    try {
                        client.getOutputStream().apply {
                            write(("HTTP/1.1 ${response.status} Test\r\nContent-Length: ${bytes.size}\r\n" +
                                (response.location?.let { "Location: $it\r\n" } ?: "") + "Connection: close\r\n\r\n").toByteArray())
                            write(bytes)
                            flush()
                        }
                    } catch (_: SocketException) { /* A cancelled or size-limited client may disconnect. */ }
                }
            } catch (closed: SocketException) { if (!socket.isClosed) throw closed }
        }
        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"
        fun reply(path: String, body: String) { routes[path] = { Response(body = body) } }
        fun redirect(path: String, location: String) { routes[path] = { Response(302, location = location) } }
        fun close() { socket.close(); worker.join(4000) }
    }
    private data class Request(val path: String, val headers: Map<String, String>)
    private data class Response(val status: Int = 200, val body: String = "", val location: String? = null)
}
