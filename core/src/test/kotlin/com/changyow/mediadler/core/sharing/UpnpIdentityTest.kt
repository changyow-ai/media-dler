package com.changyow.mediadler.core.sharing

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.net.BindException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class UpnpIdentityTest {
    @Test fun occupiedPortFailsInsteadOfChangingSavedAddresses() {
        val address = InetAddress.getByName("127.0.0.1")
        val files = object : SharedFiles {
            override fun stat(path: String): SharedEntry = error("No browse expected")
            override fun list(path: String): List<SharedEntry> = error("No browse expected")
            override fun open(path: String): SeekableByteChannel = error("No media read expected")
        }
        ServerSocket(0, 1, address).use { occupied ->
            UpnpServer(files, address, UUID.randomUUID(), listenPort = occupied.localPort).use { server ->
                assertThrows(BindException::class.java) { server.start() }
                assertEquals(0, server.httpPort)
            }
        }
    }

    @Test fun savedFolderAndMediaAddressesSurviveServerRecreation() {
        val media = Files.createTempFile("upnp-identity", ".mp4")
        val payload = "persistent media fixture".toByteArray()
        Files.write(media, payload)
        val folder = SharedEntry("/中文資料夾", "中文資料夾", true)
        val video = SharedEntry("${folder.path}/影片.mp4", "影片.mp4", false, payload.size.toLong(), mimeType = "video/mp4")
        val files = object : SharedFiles {
            override fun stat(path: String) = when (path) {
                "/" -> SharedEntry("/", "media-dler", true)
                folder.path -> folder
                video.path -> video
                else -> error("Unexpected path: $path")
            }
            override fun list(path: String) = when (path) {
                "/" -> listOf(folder)
                folder.path -> listOf(video)
                else -> error("Unexpected path: $path")
            }
            override fun open(path: String): SeekableByteChannel {
                require(path == video.path)
                return Files.newByteChannel(media)
            }
        }
        val address = InetAddress.getByName("127.0.0.1")
        val deviceId = UUID.randomUUID()
        fun snapshot(server: UpnpServer): Snapshot {
            val descriptorUrl = "http://${address.hostAddress}:${server.httpPort}/dev/$deviceId/desc"
            val descriptor = xml(request(descriptorUrl))
            val services = descriptor.getElementsByTagName("service")
            val directory = (0 until services.length).map { services.item(it) as Element }
                .single { it.getElementsByTagName("serviceType").item(0).textContent.contains("ContentDirectory") }
            val control = URL(URL(descriptorUrl), directory.getElementsByTagName("controlURL").item(0).textContent).toString()
            fun browse(id: String): org.w3c.dom.Document {
                val response = xml(request(control, """
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>$id</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>0</RequestedCount><SortCriteria></SortCriteria></u:Browse>
                    </s:Body></s:Envelope>
                """.trimIndent()))
                return xml(response.getElementsByTagName("Result").item(0).textContent.toByteArray())
            }
            val folderId = (browse("0").getElementsByTagName("container").item(0) as Element).getAttribute("id")
            val children = browse(folderId)
            return Snapshot(descriptorUrl, control, descriptor.getElementsByTagName("UDN").item(0).textContent,
                folderId, (children.getElementsByTagName("item").item(0) as Element).getAttribute("id"),
                children.getElementsByTagName("res").item(0).textContent)
        }
        try {
            val before = UpnpServer(files, address, deviceId).use { server -> server.start(); snapshot(server) }
            UpnpServer(files, address, deviceId).use { server ->
                server.start()
                assertEquals(UpnpServer.DEFAULT_HTTP_PORT, server.httpPort)
                val after = snapshot(server)
                assertEquals("Device UUID changed", before.udn, after.udn)
                assertEquals("Folder identity changed", before.folderId, after.folderId)
                assertEquals("Media identity changed", before.mediaId, after.mediaId)
                assertEquals("Saved folder descriptor address changed after restart", before.descriptorUrl, after.descriptorUrl)
                assertEquals("VLC folder control address changed after restart", before.controlUrl, after.controlUrl)
                assertEquals("Playback history media address changed after restart", before.mediaUrl, after.mediaUrl)
                assertArrayEquals(payload, request(before.mediaUrl))
                assertArrayEquals(payload.copyOfRange(4, 10), request(before.mediaUrl, range = "bytes=4-9"))
            }
        } finally { Files.deleteIfExists(media) }
    }

    private data class Snapshot(val descriptorUrl: String, val controlUrl: String, val udn: String, val folderId: String, val mediaId: String, val mediaUrl: String)

    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bytes.inputStream())

    private fun request(url: String, soap: String? = null, range: String? = null): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        try {
            range?.let { connection.setRequestProperty("Range", it) }
            if (soap != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                connection.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
                connection.outputStream.use { it.write(soap.toByteArray()) }
            }
            assertEquals(if (range == null) 200 else 206, connection.responseCode)
            return connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }
}
