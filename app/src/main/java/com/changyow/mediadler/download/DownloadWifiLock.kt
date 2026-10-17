package com.changyow.mediadler.download

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/** Each foreground owner holds its own lease so a handover cannot release the new owner's lock. */
internal class DownloadWifiLock(context: Context) {
    @Suppress("DEPRECATION") // Retain screen-off Wi-Fi protection on Android 9/11.
    private val lock = try {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "media-dler:download-wifi")
            ?.apply { setReferenceCounted(false) }
    } catch (error: RuntimeException) {
        Log.w(TAG, "Unable to create download Wi-Fi lock", error)
        null
    }

    fun acquire() {
        try { lock?.takeUnless { it.isHeld }?.acquire() }
        catch (error: RuntimeException) { Log.w(TAG, "Unable to acquire download Wi-Fi lock", error) }
    }

    fun release() {
        try { lock?.takeIf { it.isHeld }?.release() }
        catch (error: RuntimeException) { Log.w(TAG, "Unable to release download Wi-Fi lock", error) }
    }

    private companion object { const val TAG = "DownloadWifiLock" }
}
