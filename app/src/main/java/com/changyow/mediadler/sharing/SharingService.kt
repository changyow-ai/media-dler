package com.changyow.mediadler.sharing

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.changyow.mediadler.MainActivity
import com.changyow.mediadler.R
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.sharing.SftpServer
import com.changyow.mediadler.core.sharing.UpnpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

class SharingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val servers = Mutex()
    private val controller get() = appContainer.sharingController
    private var upnp: UpnpServer? = null
    private var sftp: SftpServer? = null
    private var configuration: List<Any?>? = null
    private var credentialRevision = -1
    private var startId = 0
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var connectivity: ConnectivityManager
    private var callbackRegistered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { updates.trySend(Unit) }
        override fun onLost(network: Network) { updates.trySend(Unit) }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { updates.trySend(Unit) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { updates.trySend(Unit) }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "區網分享", NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        connectivity = getSystemService(ConnectivityManager::class.java)
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), callback)
            callbackRegistered = true
        } catch (failure: Exception) { controller.stopped("無法監看區域網路：${failure.message}") }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "media-dler:sharing")
            .apply { setReferenceCounted(false) }
        scope.launch {
            for (ignored in updates) {
                try { servers.withLock { reconcile() } }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { servers.withLock { closeServers() }; controller.stopped("分享失敗：${failure.message}") }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                if (!controller.state.value.let { it.upnpEnabled || it.sftpEnabled }) {
                    servers.withLock { closeServers() }
                    withContext(Dispatchers.Main) { stopSelfResult(startId) }
                }
            }
        }
        scope.launch {
            appContainer.settingsRepository.settings.map { it.storageMode to it.safTreeUri }.distinctUntilChanged()
                .collect { updates.trySend(Unit) }
        }
        scope.launch {
            appContainer.downloadQueue.tasks.map { tasks -> tasks.filter { it.status == DownloadStatus.COMPLETED }.map { it.outputUri } }
                .distinctUntilChanged().collect { servers.withLock { upnp?.contentChanged() } }
        }
        scope.launch {
            while (isActive) {
                if (controller.state.value.let { it.upnpEnabled || it.sftpEnabled }) wake?.acquire(10 * 60_000L)
                delay(4 * 60_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        startId = id
        if (intent?.action == ACTION_STOP) controller.stopped()
        updates.trySend(Unit)
        return START_NOT_STICKY
    }

    private suspend fun reconcile() {
        val wanted = controller.state.value
        val settings = appContainer.settingsRepository.settings.first()
        val address = localAddress()
        val next = listOf(address?.hostAddress, settings.storageMode, settings.safTreeUri)
        if (configuration != next) { closeServers(); configuration = next }
        if (!wanted.upnpEnabled) {
            upnp?.close(); upnp = null; releaseMulticast()
            if (wanted.upnpStatus == "停止中…") controller.report(true, "已停止")
        }
        if (!wanted.sftpEnabled || credentialRevision != wanted.credentialRevision) { sftp?.close(); sftp = null }
        if (!wanted.sftpEnabled && wanted.sftpStatus == "停止中…") controller.report(false, "已停止")
        if (address == null) {
            if (wanted.upnpEnabled) controller.report(true, "等待 Wi-Fi 或乙太網路")
            if (wanted.sftpEnabled) controller.report(false, "等待 Wi-Fi 或乙太網路")
            return
        }
        val files = AndroidSharedFiles(this, settings)
        if (wanted.upnpEnabled && upnp == null) {
            try {
                files.list("/") // Fail before advertising a revoked/unreadable root.
                multicast = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createMulticastLock("media-dler:upnp")
                    .apply { setReferenceCounted(false); acquire() }
                val started = UpnpServer(files, address, controller.deviceId)
                started.start()
                upnp = started
                controller.report(true, "執行中 · ${address.hostAddress}:${started.httpPort}")
            } catch (failure: LinkageError) {
                releaseMulticast()
                Log.e("SharingService", "UPnP initialization failed", failure)
                controller.report(true, "啟動失敗：協定套件無法初始化", true)
            } catch (failure: Exception) { releaseMulticast(); controller.report(true, "啟動失敗：${failure.message}", true) }
        }
        if (wanted.sftpEnabled && sftp == null) {
            try {
                files.list("/")
                val credentials = requireNotNull(controller.credentials()) { "請重新設定登入密碼" }
                val started = SftpServer(files, address, wanted.port, File(noBackupFilesDir, "sftp-host-key"), credentials::accepts)
                started.start()
                sftp = started
                credentialRevision = wanted.credentialRevision
                controller.report(false, "sftp://${wanted.username}@${address.hostAddress}:${wanted.port}")
            } catch (failure: LinkageError) {
                Log.e("SharingService", "SFTP initialization failed", failure)
                controller.report(false, "啟動失敗：協定套件無法初始化", true)
            } catch (failure: Exception) { controller.report(false, "啟動失敗：${failure.message}", true) }
        }
    }

    @Suppress("DEPRECATION")
    private fun localAddress(): InetAddress? = connectivity.allNetworks
        .sortedBy { if (it == connectivity.activeNetwork) 0 else 1 }.firstNotNullOfOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                return@firstNotNullOfOrNull null
            connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        }

    private fun notification(): Notification {
        val state = controller.state.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, SharingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = listOfNotNull(if (state.upnpEnabled) "UPnP：${state.upnpStatus}" else null,
            if (state.sftpEnabled) "SFTP：${state.sftpStatus}" else null).joinToString("\n").ifEmpty { "正在停止分享" }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("media-dler 區網分享").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).addAction(0, "停止分享", stop).build()
    }

    private fun releaseMulticast() { multicast?.let { if (it.isHeld) it.release() }; multicast = null }
    private fun closeServers() {
        runCatching { upnp?.close() }; upnp = null
        runCatching { sftp?.close() }; sftp = null
        releaseMulticast()
    }
    override fun onDestroy() {
        if (callbackRegistered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        updates.close()
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        controller.serviceDestroyed()
        // Service shutdown must not block the main thread on protocol worker joins.
        Thread({ runBlocking { servers.withLock { closeServers() } } }, "sharing-stop").start()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "local_sharing"
        private const val NOTIFICATION_ID = 80_001
        private const val ACTION_STOP = "com.changyow.mediadler.STOP_SHARING"
    }
}
