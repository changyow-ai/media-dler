package com.changyow.mediadler.core.sharing

import org.jupnp.DefaultUpnpServiceConfiguration
import org.jupnp.UpnpServiceImpl
import org.jupnp.binding.annotations.AnnotationLocalServiceBinder
import org.jupnp.model.DefaultServiceManager
import org.jupnp.model.meta.*
import org.jupnp.model.types.*
import org.jupnp.support.connectionmanager.ConnectionManagerService
import org.jupnp.support.contentdirectory.AbstractContentDirectoryService
import org.jupnp.support.contentdirectory.ContentDirectoryErrorCode
import org.jupnp.support.contentdirectory.ContentDirectoryException
import org.jupnp.support.contentdirectory.DIDLParser
import org.jupnp.support.model.*
import org.jupnp.support.model.container.Container
import org.jupnp.support.model.item.Item
import org.jupnp.transport.spi.*
import java.io.Closeable
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Base64
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import javax.xml.parsers.SAXParserFactory

class UpnpServer(
    private val files: SharedFiles,
    private val address: InetAddress,
    private val deviceId: UUID,
    private val name: String = "media-dler",
) : Closeable {
    private var service: UpnpServiceImpl? = null
    private var directory: FolderDirectory? = null
    private var transport: UpnpHttpServer? = null
    val httpPort: Int get() = transport?.port ?: 0

    fun start() {
        check(service == null)
        val config = object : DefaultUpnpServiceConfiguration(0, 0, false) {
            // This device only serves files; do not discover/fetch other LAN device descriptors.
            override fun getExclusiveServiceTypes(): Array<ServiceType>? = null
            override fun createNetworkAddressFactory(streamListenPort: Int, multicastResponsePort: Int): NetworkAddressFactory =
                SingleAddressNetwork(address)
            override fun createStreamClient(): StreamClient<*> = UpnpHttpClient(address)
            override fun createStreamServer(networkAddressFactory: NetworkAddressFactory): StreamServer<*> =
                UpnpHttpServer(files).also { transport = it }
        }
        val upnp = UpnpServiceImpl(config)
        service = upnp
        try {
            upnp.startup()
            check(httpPort > 0 && upnp.router.isEnabled) { "無法啟動 UPnP 網路服務" }
            val content = FolderDirectory(files, "http://${address.hostAddress}:$httpPort")
            directory = content
            val binder = AnnotationLocalServiceBinder()
            @Suppress("UNCHECKED_CAST")
            val contentService = binder.read(AbstractContentDirectoryService::class.java) as LocalService<AbstractContentDirectoryService>
            contentService.manager = object : DefaultServiceManager<AbstractContentDirectoryService>(contentService, AbstractContentDirectoryService::class.java) {
                override fun createServiceInstance(): AbstractContentDirectoryService = content
            }
            @Suppress("UNCHECKED_CAST")
            val connectionService = binder.read(ConnectionManagerService::class.java) as LocalService<ConnectionManagerService>
            connectionService.manager = object : DefaultServiceManager<ConnectionManagerService>(connectionService, ConnectionManagerService::class.java) {
                override fun createServiceInstance() = ConnectionManagerService(
                    ProtocolInfos("http-get:*:*:*"), ProtocolInfos(),
                )
            }
            val device = LocalDevice(
                DeviceIdentity(UDN(deviceId)), UDADeviceType("MediaServer", 1),
                DeviceDetails(name, ManufacturerDetails("media-dler"), ModelDetails("media-dler", "Downloaded media", "1")),
                arrayOf<LocalService<*>>(contentService, connectionService),
            )
            upnp.registry.addDevice(device)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun contentChanged() { directory?.changed() }
    override fun close() {
        service?.shutdown()
        service = null
        directory = null
        transport = null
    }
}

internal fun objectId(path: String): String = if (path == "/") "0" else
    Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
internal fun objectPath(id: String): String = if (id == "0") "/" else {
    require(id.length <= 8192)
    SharePath.normalize(String(Base64.getUrlDecoder().decode(id), Charsets.UTF_8))
}

private class FolderDirectory(private val files: SharedFiles, private val baseUrl: String) :
    AbstractContentDirectoryService(emptyList(), listOf("dc:title")) {
    fun changed() = changeSystemUpdateID()
    override fun browse(id: String, flag: BrowseFlag?, filter: String?, first: Long, count: Long, order: Array<SortCriterion>): BrowseResult {
        try {
            if (flag == null) throw ContentDirectoryException(ContentDirectoryErrorCode.CANNOT_PROCESS, "Invalid browse flag")
            if (order.any { it.propertyName != "dc:title" })
                throw ContentDirectoryException(ContentDirectoryErrorCode.UNSUPPORTED_SORT_CRITERIA, "Only dc:title is supported")
            val path = objectPath(id)
            val entry = files.stat(path)
            val all = if (flag == BrowseFlag.METADATA) listOf(entry) else {
                if (!entry.directory) throw ContentDirectoryException(ContentDirectoryErrorCode.NO_SUCH_OBJECT, "Not a folder")
                files.list(path).filter { it.directory || it.mimeType.substringBefore('/') in setOf("video", "audio", "image") }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    .let { if (order.firstOrNull()?.isAscending == false) it.reversed() else it }
            }
            val from = if (flag == BrowseFlag.METADATA) 0 else first.coerceAtMost(all.size.toLong()).toInt()
            val to = if (flag == BrowseFlag.METADATA || count == 0L) all.size else
                (from.toLong() + count.coerceAtMost(all.size.toLong())).coerceAtMost(all.size.toLong()).toInt()
            val result = DIDLContent()
            all.subList(from, to).forEach { item ->
                val parent = if (item.path == "/") "-1" else objectId(item.path.substringBeforeLast('/').ifEmpty { "/" })
                if (item.directory) result.addContainer(Container(objectId(item.path), parent, item.name, "media-dler",
                    DIDLObject.Class("object.container.storageFolder"), null).apply { isRestricted = true })
                else {
                    val clazz = when (item.mimeType.substringBefore('/')) {
                        "video" -> "object.item.videoItem"
                        "audio" -> "object.item.audioItem"
                        "image" -> "object.item.imageItem"
                        else -> "object.item"
                    }
                    result.addItem(Item(objectId(item.path), parent, item.name, "media-dler", DIDLObject.Class(clazz)).apply {
                        isRestricted = true
                        addResource(Res(ProtocolInfo("http-get:*:${item.mimeType}:*"), item.size,
                            "$baseUrl/media/${objectId(item.path)}"))
                    })
                }
            }
            val parser = object : DIDLParser() {
                // Android has no default XMLReaderFactory driver; use its supported JAXP factory.
                override fun create() = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
                    .newSAXParser().xmlReader
            }
            return BrowseResult(parser.generate(result), (to - from).toLong(), all.size.toLong(), systemUpdateID.value)
        } catch (failure: ContentDirectoryException) { throw failure }
        catch (failure: Exception) {
            if (failure !is java.io.IOException && failure !is IllegalArgumentException && failure !is SecurityException)
                Logger.getLogger("UpnpServer").log(Level.WARNING, "Unable to browse shared directory", failure)
            throw ContentDirectoryException(ContentDirectoryErrorCode.NO_SUCH_OBJECT, "File or folder unavailable")
        }
    }
}

/** Bind discovery and HTTP to the same selected Wi-Fi/Ethernet address, including test loopback. */
private class SingleAddressNetwork(private val address: InetAddress) : NetworkAddressFactory {
    private val iface = requireNotNull(NetworkInterface.getByInetAddress(address))
    override fun getMulticastGroup(): InetAddress = InetAddress.getByName("239.255.255.250")
    override fun getMulticastPort() = 1900
    override fun getMulticastResponsePort() = 0
    override fun getStreamListenPort() = 0
    override fun getNetworkInterfaces(): MutableIterator<NetworkInterface> = mutableListOf(iface).iterator()
    override fun getBindAddresses(): MutableIterator<InetAddress> = mutableListOf(address).iterator()
    override fun hasUsableNetwork() = true
    override fun getAddressNetworkPrefixLength(a: InetAddress): Short? = iface.interfaceAddresses.find { it.address == a }?.networkPrefixLength
    override fun getHardwareAddress(a: InetAddress): ByteArray? = iface.hardwareAddress
    override fun getBroadcastAddress(a: InetAddress): InetAddress? = iface.interfaceAddresses.find { it.address == a }?.broadcast
    override fun getLocalAddress(network: NetworkInterface, ipv6: Boolean, remote: InetAddress) = address
    override fun logInterfaceInformation() = Unit
}
