package com.changyow.mediadler.sharing

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.changyow.mediadler.MainActivity
import com.changyow.mediadler.R
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.sharing.SftpServer
import com.changyow.mediadler.core.sharing.SharingActivity
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
    private val commandReady = CompletableDeferred<Unit>()
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
    private var wifiWake: WifiManager.WifiLock? = null
    private lateinit var connectivity: ConnectivityManager
    private var callbackRegistered = false
    private val activity = SharingActivity(SystemClock::elapsedRealtime) { revision, timestamp -> controller.recordRead(revision, timestamp) }
    private var sessionRevision = -1L
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
        @Suppress("DEPRECATION") // Preserve screen-off radio protection on Android 9/11.
        val wifiLock = runCatching {
            applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "media-dler:sharing-wifi")
                ?.apply { setReferenceCounted(false) }
        }.onFailure { Log.w("SharingService", "Unable to create sharing Wi-Fi lock", it) }.getOrNull()
        wifiWake = wifiLock
        scope.launch {
            // A cold notification STOP must clear recovery before any listener can open.
            commandReady.await()
            for (ignored in updates) {
                try { servers.withLock { reconcile(); stopIfIdle() } }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { servers.withLock { closeServers() }; controller.stopped("分享失敗：${failure.message}") }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                if (!controller.state.value.let { it.upnpEnabled || it.sftpEnabled }) {
                    servers.withLock { closeServers() }
                    withContext(Dispatchers.Main) {
                        if (!controller.state.value.let { it.upnpEnabled || it.sftpEnabled }) stopSelfResult(startId)
                    }
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
            commandReady.await()
            while (isActive) {
                wake?.let { lock ->
                    synchronized(lock) {
                        if (isActive && controller.state.value.let { it.upnpEnabled || it.sftpEnabled }) lock.acquire(10 * 60_000L)
                    }
                }
                wifiWake?.let { lock ->
                    synchronized(lock) {
                        if (isActive && controller.state.value.let { it.upnpEnabled || it.sftpEnabled } && !lock.isHeld) {
                            runCatching { lock.acquire() }.onFailure { Log.w("SharingService", "Unable to acquire sharing Wi-Fi lock", it) }
                        }
                    }
                }
                delay(4 * 60_000L)
            }
        }
        scope.launch {
            while (isActive) {
                delay(60_000)
                updates.trySend(Unit)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        startId = id
        if (intent?.action == ACTION_STOP) controller.stopped()
        commandReady.complete(Unit)
        updates.trySend(Unit)
        return if (controller.shouldRestart()) START_STICKY else START_NOT_STICKY
    }

    private suspend fun reconcile() {
        val wanted = controller.state.value
        if (sessionRevision != wanted.sessionRevision) {
            val previousActivity = controller.activityStartMillis(wanted.sessionRevision) ?: return
            activity.reset(previousActivity, wanted.sessionRevision)
            sessionRevision = wanted.sessionRevision
        }
        if (stopIfIdle()) return
        if (!wanted.let { it.upnpEnabled || it.sftpEnabled }) {
            closeServers()
            if (wanted.upnpStatus == "停止中…") controller.report(true, "已停止", expectedRevision = wanted.sessionRevision)
            if (wanted.sftpStatus == "停止中…") controller.report(false, "已停止", expectedRevision = wanted.sessionRevision)
            return
        }
        val settings = appContainer.settingsRepository.settings.first()
        val address = localAddress()
        val next = listOf(address?.hostAddress, settings.storageMode, settings.safTreeUri)
        if (configuration != next) { closeServers(); configuration = next }
        if (!wanted.upnpEnabled) {
            upnp?.close(); upnp = null; releaseMulticast()
            if (wanted.upnpStatus == "停止中…") controller.report(true, "已停止", expectedRevision = wanted.sessionRevision)
        }
        if (!wanted.sftpEnabled || credentialRevision != wanted.credentialRevision) { sftp?.close(); sftp = null }
        if (!wanted.sftpEnabled && wanted.sftpStatus == "停止中…") controller.report(false, "已停止", expectedRevision = wanted.sessionRevision)
        if (address == null) {
            if (wanted.upnpEnabled) controller.report(true, "等待 Wi-Fi 或乙太網路", expectedRevision = wanted.sessionRevision)
            if (wanted.sftpEnabled) controller.report(false, "等待 Wi-Fi 或乙太網路", expectedRevision = wanted.sessionRevision)
            return
        }
        val files = activity.track(AndroidSharedFiles(this, settings))
        if (wanted.upnpEnabled && upnp == null) {
            try {
                files.list("/") // Fail before advertising a revoked/unreadable root.
                multicast = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createMulticastLock("media-dler:upnp")
                    .apply { setReferenceCounted(false); acquire() }
                val started = UpnpServer(files, address, controller.deviceId)
                started.start()
                upnp = started
                Log.i("SharingService", "UPnP listening on ${address.hostAddress}:${started.httpPort}")
                controller.report(true, "執行中 · ${address.hostAddress}:${started.httpPort}", expectedRevision = wanted.sessionRevision)
            } catch (failure: LinkageError) {
                releaseMulticast()
                Log.e("SharingService", "UPnP initialization failed", failure)
                controller.report(true, "啟動失敗：協定套件無法初始化", true, wanted.sessionRevision)
            } catch (failure: Exception) { releaseMulticast(); controller.report(true, "啟動失敗：${failure.message}", true, wanted.sessionRevision) }
        }
        if (wanted.sftpEnabled && sftp == null) {
            try {
                files.list("/")
                val credentials = requireNotNull(controller.credentials()) { "請重新設定登入密碼" }
                val started = SftpServer(files, address, wanted.port, File(noBackupFilesDir, "sftp-host-key"), credentials::accepts)
                started.start()
                sftp = started
                credentialRevision = wanted.credentialRevision
                Log.i("SharingService", "SFTP listening on ${address.hostAddress}:${wanted.port}")
                controller.report(false, "sftp://${wanted.username}@${address.hostAddress}:${wanted.port}", expectedRevision = wanted.sessionRevision)
            } catch (failure: LinkageError) {
                Log.e("SharingService", "SFTP initialization failed", failure)
                controller.report(false, "啟動失敗：協定套件無法初始化", true, wanted.sessionRevision)
            } catch (failure: Exception) { controller.report(false, "啟動失敗：${failure.message}", true, wanted.sessionRevision) }
        }
        upnp?.let { controller.report(true, "執行中 · ${address.hostAddress}:${it.httpPort}", expectedRevision = wanted.sessionRevision) }
        sftp?.let { controller.report(false, "sftp://${wanted.username}@${address.hostAddress}:${wanted.port}", expectedRevision = wanted.sessionRevision) }
    }

    private fun stopIfIdle(): Boolean {
        val state = controller.state.value
        if (state.sessionRevision != sessionRevision || !state.let { it.upnpEnabled || it.sftpEnabled }) return false
        if (!activity.expireIfIdle(state.idleTimeout.millis) { controller.stopIfIdle(state) }) return false
        closeServers()
        return true
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
        wake?.let { lock -> synchronized(lock) { if (lock.isHeld) lock.release() } }
        wifiWake?.let { lock ->
            synchronized(lock) {
                runCatching { if (lock.isHeld) lock.release() }
                    .onFailure { Log.w("SharingService", "Unable to release sharing Wi-Fi lock", it) }
            }
        }
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
