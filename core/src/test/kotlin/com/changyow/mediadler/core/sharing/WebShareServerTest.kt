package com.changyow.mediadler.core.sharing

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.BindException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.StandardOpenOption

class WebShareServerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val address = InetAddress.getByName("127.0.0.1")
    private fun server(files: SharedFiles, port: Int = 0) = WebShareServer(files, address, Long.MAX_VALUE, System::currentTimeMillis, port)

    @Test fun browserListingNavigatesUnicodeFoldersEscapesNamesAndHidesTemporaryFiles() = fixture { files ->
        val folder = File(files.root, "中文資料夾").apply { mkdir() }
        val name = "文件 <script>&\"'.pdf"
        File(folder, name).writeText("document")
        File(files.root, ".private").writeText("hidden")
        File(files.root, "incomplete.part").writeText("pending")
        File(files.root, "media-dler-file.partial.mp4").writeText("pending")
        server(files).use { server ->
            server.start()
            val root = request(server, "/")
            assertEquals(200, root.code)
            val rootHtml = root.body.toString(Charsets.UTF_8)
            val folderUrl = Regex("href=\"([^\"]+)\">中文資料夾/").find(rootHtml)!!.groupValues[1]
            assertFalse(rootHtml.contains(".private"))
            assertFalse(rootHtml.contains("incomplete.part"))
            assertFalse(rootHtml.contains("media-dler-file.partial.mp4"))
            val page = request(server, folderUrl)
            assertEquals(200, page.code)
            val html = page.body.toString(Charsets.UTF_8)
            assertTrue(html.contains("文件 &lt;script&gt;&amp;&quot;&#39;.pdf"))
            assertFalse(html.contains("<script>"))
            assertTrue(html.contains("href=\"/\">↑ 上一層"))
            assertTrue(page.header("Content-Security-Policy").contains("default-src 'none'"))
            assertEquals("no-store", page.header("Cache-Control"))
            assertTrue(html.contains("name=\"viewport\""))
        }
    }

    @Test fun downloadUsesOriginalBytesAndUnicodeAttachmentNameForAnyFileType() = fixture { files ->
        val name = "中文 & \"notes\".html"
        val payload = "<script>untrusted content</script>\u0000中文".toByteArray(Charsets.UTF_8)
        File(files.root, name).writeBytes(payload)
        server(files).use { server ->
            server.start()
            val result = request(server, download("/$name"))
            assertEquals(200, result.code)
            assertArrayEquals(payload, result.body)
            assertTrue(result.header("Content-Disposition").startsWith("attachment; filename=\""))
            assertTrue(result.header("Content-Disposition").contains("filename*=UTF-8''%E4%B8%AD%E6%96%87%20%26%20%22notes%22.html"))
            assertEquals("application/octet-stream", result.header("Content-Type"))
            assertEquals("nosniff", result.header("X-Content-Type-Options"))
            assertEquals(payload.size.toString(), result.header("Content-Length"))
            assertEquals("", result.header("Access-Control-Allow-Origin"))
        }
    }

    @Test fun headRangesAndIfRangeReuseTheStreamingFileContract() = fixture { files ->
        val payload = ByteArray(50_000) { (it % 251).toByte() }
        File(files.root, "data.zip").writeBytes(payload)
        server(files).use { server ->
            server.start()
            val path = download("/data.zip")
            val head = request(server, path, method = "HEAD")
            assertEquals(200, head.code)
            assertEquals(payload.size.toString(), head.header("Content-Length"))
            assertTrue(head.body.isEmpty())
            assertEquals(0, files.openCount)
            assertEquals(200, request(server, "/", method = "HEAD").code)
            val range = request(server, path, headers = mapOf("Range" to "bytes=40000-40019"))
            assertEquals(206, range.code)
            assertEquals("bytes 40000-40019/50000", range.header("Content-Range"))
            assertArrayEquals(payload.copyOfRange(40000, 40020), range.body)
            assertEquals("bytes", range.header("Accept-Ranges"))
            assertArrayEquals(payload.takeLast(9).toByteArray(), request(server, path, headers = mapOf("Range" to "bytes=-9")).body)
            val resume = request(server, path, headers = mapOf("Range" to "bytes=10-", "If-Range" to head.header("ETag")))
            assertEquals(206, resume.code)
            assertArrayEquals(payload.copyOfRange(10, payload.size), resume.body)
            val stale = request(server, path, headers = mapOf("Range" to "bytes=10-", "If-Range" to "\"stale\""))
            assertEquals(200, stale.code)
            assertArrayEquals(payload, stale.body)
            val invalid = request(server, path, headers = mapOf("Range" to "bytes=50000-"))
            assertEquals(416, invalid.code)
            assertEquals("bytes */50000", invalid.header("Content-Range"))
        }
    }

    @Test fun emptyFilesAndFoldersAreRepresentedWithoutReadingOutsideTheirBounds() = fixture { files ->
        File(files.root, "empty").mkdir()
        File(files.root, "empty.txt").writeBytes(byteArrayOf())
        server(files).use { server ->
            server.start()
            val result = request(server, download("/empty.txt"))
            assertEquals(200, result.code)
            assertEquals("0", result.header("Content-Length"))
            assertTrue(result.body.isEmpty())
            val partial = request(server, download("/empty.txt"), headers = mapOf("Range" to "bytes=0-"))
            assertEquals(416, partial.code)
            assertEquals("bytes */0", partial.header("Content-Range"))
            assertTrue(request(server, "/folder/${objectId("/empty")}").body.toString(Charsets.UTF_8).contains("沒有可分享的檔案"))
            assertEquals(404, request(server, download("/empty")).code)
        }
    }

    @Test fun malformedPathsOutsideRootAndUnpublishedFilesCannotBeRead() = fixture { files ->
        File(files.root, ".private").writeText("secret")
        File(files.root, "unfinished.tmp").writeText("secret")
        server(files).use { server ->
            server.start()
            assertEquals(400, request(server, "/download/not!base64").code)
            assertEquals(400, request(server, "/download/").code)
            assertEquals(403, request(server, download("/../../outside")).code)
            assertEquals(404, request(server, download("/.private")).code)
            assertEquals(404, request(server, download("/unfinished.tmp")).code)
            assertEquals(404, request(server, download("/missing")).code)
            assertEquals(404, request(server, "/unrecognized").code)
            assertEquals(0, files.openCount)
        }
    }

    @Test fun writeMethodsAndForeignHostsAreRejected() = fixture { files ->
        File(files.root, "keep.txt").writeText("unchanged")
        server(files).use { server ->
            server.start()
            for (method in listOf("POST", "PUT", "DELETE", "OPTIONS")) {
                val response = request(server, download("/keep.txt"), method)
                assertEquals(method, 405, response.code)
                assertEquals("GET, HEAD", response.header("Allow"))
            }
            for ((host, code) in listOf("attacker.example:${server.httpPort}" to 421, "127.0.0.1:${server.httpPort + 1}" to 403)) {
                Socket(address, server.httpPort).use { socket ->
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                    val status = socket.getInputStream().bufferedReader().readLine()
                    assertTrue(status, status.startsWith("HTTP/1.1 $code "))
                }
            }
            assertEquals("unchanged", File(files.root, "keep.txt").readText())
            assertEquals(0, files.openCount)
        }
    }

    @Test fun exactDeadlineExpiresEveryRouteWithoutExtendingOnRead() = fixture { files ->
        var now = 1_000L
        File(files.root, "document.zip").writeText("bytes")
        WebShareServer(files, address, 11_000, { now }, 0).use { server ->
            server.start()
            assertEquals(200, request(server, "/").code)
            now = 10_999
            assertEquals(200, request(server, download("/document.zip")).code)
            val opens = files.openCount
            val stats = files.statCount
            val lists = files.listCount
            now = 11_000
            for (path in listOf("/", "/folder/0", download("/document.zip"), "/unrecognized")) {
                assertEquals(410, request(server, path).code)
                assertEquals(410, request(server, path, method = "HEAD").code)
            }
            assertEquals(410, request(server, "/", method = "POST").code)
            assertEquals(opens, files.openCount)
            assertEquals(stats, files.statCount)
            assertEquals(lists, files.listCount)
        }
    }

    @Test fun anAlreadyOpenStreamCannotReadAdditionalBytesAfterItsDeadline() = fixture { files ->
        File(files.root, "document.zip").writeBytes(ByteArray(20_000) { 42 })
        var expired = false
        val request = org.apache.hc.core5.http.message.BasicClassicHttpRequest("GET", "/download")
        val response = org.apache.hc.core5.http.message.BasicClassicHttpResponse(200)
        serveSharedFile(files, "/document.zip", request, response, download = true,
            checkReadable = { if (expired) throw java.io.IOException("Sharing session expired") })
        response.entity.use { entity ->
            assertEquals(8, entity.content.read(ByteArray(8)))
            expired = true
            assertThrows(java.io.IOException::class.java) { entity.content.read(ByteArray(8)) }
        }
    }

    @Test fun occupiedPortFailsAndClosingStopsTheListener() = fixture { files ->
        ServerSocket(0, 1, address).use { occupied ->
            server(files, occupied.localPort).use { server ->
                assertThrows(BindException::class.java) { server.start() }
                assertEquals(0, server.httpPort)
            }
        }
        val server = server(files)
        server.start()
        val port = server.httpPort
        assertTrue(port > 0)
        server.close()
        assertEquals(0, server.httpPort)
        assertThrows(java.io.IOException::class.java) { Socket(address, port).use { } }
    }

    private fun fixture(test: (FolderFiles) -> Unit) = test(FolderFiles(temporary.newFolder()))
    private fun download(path: String) = "/download/${objectId(path)}"

    private data class Response(val code: Int, val headers: Map<String?, List<String>>, val body: ByteArray) {
        fun header(name: String): String = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull().orEmpty()
    }

    private fun request(server: WebShareServer, path: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Response {
        val connection = URL("http://${address.hostAddress}:${server.httpPort}$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = method
        headers.forEach(connection::setRequestProperty)
        return try {
            val code = connection.responseCode
            Response(code, connection.headerFields, (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() } ?: byteArrayOf())
        } finally { connection.disconnect() }
    }

    private class FolderFiles(val root: File) : SharedFiles {
        var openCount = 0
        var statCount = 0
        var listCount = 0
        private fun file(path: String): File {
            val normalized = SharePath.normalize(path)
            val file = File(root, normalized.removePrefix("/")).canonicalFile
            if (!file.toPath().startsWith(root.canonicalFile.toPath())) throw AccessDeniedException(path)
            if (!file.exists() || normalized.split('/').any { it.isNotEmpty() && !SharePath.isPublished(it) }) throw NoSuchFileException(path)
            return file
        }
        override fun stat(path: String): SharedEntry {
            statCount++
            val normalized = SharePath.normalize(path)
            val file = file(normalized)
            return SharedEntry(normalized, file.name, file.isDirectory, if (file.isFile) file.length() else 0,
                file.lastModified(), Files.probeContentType(file.toPath()) ?: "application/octet-stream")
        }
        override fun list(path: String): List<SharedEntry> {
            listCount++
            return file(path).listFiles()?.filter { SharePath.isPublished(it.name) }
                ?.map { stat(SharePath.child(path, it.name)) } ?: throw NotDirectoryException(path)
        }
        override fun open(path: String): SeekableByteChannel {
            openCount++
            return Files.newByteChannel(file(path).toPath(), StandardOpenOption.READ)
        }
    }
}
