package com.changyow.mediadler.sharing

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.MainActivity
import com.changyow.mediadler.MediaDlerApp
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.StorageMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.apache.sshd.client.SshClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.Socket
import java.net.InetSocketAddress
import java.net.URL
import java.time.Duration
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Device integration: real DocumentsProvider/MediaStore, foreground service and network sockets.
 * Run on a disposable emulator. Optional -e treeUri <persistently granted local tree> exercises SAF.
 */
@RunWith(AndroidJUnit4::class)
class SharingDeviceTest {
    @Test fun transferAndServiceLifecycle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = context.appContainer
        val controller = container.sharingController
        val original = container.settingsRepository.settings.first()
        val treeUri = InstrumentationRegistry.getArguments().getString("treeUri")?.let(Uri::parse)
        val tree = treeUri?.let { requireNotNull(DocumentFile.fromTreeUri(context, it)) }
        val folder = "sharing-smoke-${System.currentTimeMillis()}"
        val name = "中文 & 空白.mp4"
        val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        val tail = "AFTER_FOUR_GIB".toByteArray()
        val offset = 4L * 1024 * 1024 * 1024 + 37
        val created = mutableListOf<Uri>()
        val safFolder = tree?.let { requireNotNull(it.createDirectory(folder)) { "Grant the SAF tree in app settings before running this test" } }

        fun create(filename: String, pending: Boolean = false): Uri {
            val uri = if (safFolder != null) requireNotNull(safFolder.createFile("video/mp4", filename)).uri else {
                requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/media-dler/$folder/")
                    put(MediaStore.Downloads.IS_PENDING, if (pending) 1 else 0)
                }))
            }
            created += uri
            return uri
        }

        suspend fun ready(withSftp: Boolean = true): SharingState = withTimeout(150_000) {
            controller.state.first { (it.upnpStatus.startsWith("執行中") && (!withSftp || it.sftpStatus.startsWith("sftp://"))) ||
                it.upnpStatus.startsWith("啟動失敗") || it.sftpStatus.startsWith("啟動失敗") }
        }.also { assertTrue(it.toString(), it.upnpStatus.startsWith("執行中") && (!withSftp || it.sftpStatus.startsWith("sftp://"))) }

        try {
            val small = create(name)
            context.contentResolver.openOutputStream(small)!!.use { it.write(payload) }
            val large = create("large.mp4")
            ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(large, "rw")!!).use {
                it.channel.position(offset); it.write(tail)
            }
            create("media-dler-incomplete.partial.mp4", pending = true)
            container.settingsRepository.update { it.copy(storageMode = if (tree == null) StorageMode.DOWNLOADS else StorageMode.SAF,
                safTreeUri = treeUri?.toString()) }
            val storage = AndroidSharedFiles(context, container.settingsRepository.settings.first())
            assertEquals(2, storage.list("/$folder").size)
            assertEquals(offset + tail.size, storage.stat("/$folder/large.mp4").size)

            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(120_000) {
                while (!(context.applicationContext as MediaDlerApp).isForeground) delay(100)
            }
            controller.setEnabled(context, true, true)
            val state = ready(withSftp = false)
            val base = "http://" + state.upnpStatus.substringAfter("· ")
            val host = URL(base).host
            fun get(path: String, range: String? = null, soap: String? = null): ByteArray {
                val connection = URL(base + path).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000; connection.readTimeout = 30_000
                range?.let { connection.setRequestProperty("Range", it) }
                if (soap != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                    connection.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
                    connection.outputStream.use { it.write(soap.toByteArray()) }
                }
                try {
                    val status = connection.responseCode
                    assertEquals(connection.errorStream?.bufferedReader()?.use { it.readText() }, if (range == null) 200 else 206, status)
                    return connection.inputStream.use { it.readBytes() }
                } finally { connection.disconnect() }
            }
            fun resource(filename: String) = "/media/" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("/$folder/$filename".toByteArray())
            fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bytes.inputStream())
            val descriptor = xml(get("/dev/${controller.deviceId}/desc"))
            val services = descriptor.getElementsByTagName("service")
            val directory = (0 until services.length).map { services.item(it) as Element }
                .first { it.getElementsByTagName("serviceType").item(0).textContent.contains("ContentDirectory") }
            val objectId = Base64.getUrlEncoder().withoutPadding().encodeToString("/$folder".toByteArray())
            val browse = xml(get(directory.getElementsByTagName("controlURL").item(0).textContent, soap = """
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>
                <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>$objectId</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>0</RequestedCount><SortCriteria></SortCriteria></u:Browse>
                </s:Body></s:Envelope>
            """.trimIndent()))
            assertEquals("2", browse.getElementsByTagName("NumberReturned").item(0).textContent)
            assertTrue(browse.getElementsByTagName("Result").item(0).textContent.contains("中文"))
            assertArrayEquals(payload, get(resource(name)))
            assertArrayEquals(payload.copyOfRange(12345, 23456), get(resource(name), "bytes=12345-23455"))
            assertArrayEquals(tail, get(resource("large.mp4"), "bytes=$offset-"))

            controller.saveLogin(context, "media", "device-test-password", 22222)
            controller.setEnabled(context, false, true)
            ready()
            val ssh = SshClient.setUpDefaultClient()
            ssh.serverKeyVerifier = org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, _, _ -> true }
            ssh.start()
            try {
                ssh.connect("media", host, 22222).verify(Duration.ofSeconds(60)).session.use { session ->
                    session.addPasswordIdentity("device-test-password")
                    session.auth().verify(Duration.ofSeconds(180))
                    // Keep authentication timing separate from background transfer on slow emulators.
                    assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                    withTimeout(60_000) {
                        while ((context.applicationContext as MediaDlerApp).isForeground) delay(100)
                    }
                    assertArrayEquals(payload.copyOfRange(100, 200), get(resource(name), "bytes=100-199"))
                    SftpClientFactory.instance().createSftpClient(session).use { sftp ->
                        assertArrayEquals(payload, sftp.read("/$folder/$name").use { it.readBytes() })
                        sftp.open("/$folder/large.mp4").use { handle ->
                            val result = ByteArray(tail.size)
                            assertEquals(tail.size, sftp.read(handle, offset, result))
                            assertArrayEquals(tail, result)
                        }
                        try { sftp.remove("/$folder/$name"); fail("SFTP delete was accepted") } catch (_: java.io.IOException) { }
                    }
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    withTimeout(120_000) {
                        while (!(context.applicationContext as MediaDlerApp).isForeground) delay(100)
                    }
                    // The other service remains usable when UPnP is disabled.
                    controller.setEnabled(context, true, false)
                    withTimeout(30_000) { controller.state.first { it.upnpStatus == "已停止" } }
                    SftpClientFactory.instance().createSftpClient(session).use { sftp -> assertEquals(payload.size.toLong(), sftp.stat("/$folder/$name").size) }
                }
            } finally { ssh.stop() }

            controller.setEnabled(context, true, true)
            ready()
            controller.setEnabled(context, true, false)
            controller.setEnabled(context, false, false)
            withTimeout(30_000) { controller.state.first { it.upnpStatus == "已停止" && it.sftpStatus == "已停止" } }
            withTimeout(30_000) {
                while (runCatching { Socket().use { it.connect(InetSocketAddress(host, 22222), 500) } }.isSuccess) delay(100)
            }
        } catch (failure: Exception) {
            throw AssertionError("分享狀態：${controller.state.value}", failure)
        } finally {
            controller.setEnabled(context, true, false)
            controller.setEnabled(context, false, false)
            context.stopService(Intent(context, SharingService::class.java))
            if (safFolder != null) safFolder.delete() else created.forEach { context.contentResolver.delete(it, null, null) }
            container.settingsRepository.update { original }
        }
    }
}
