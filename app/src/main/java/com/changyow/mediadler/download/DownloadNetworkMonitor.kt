package com.changyow.mediadler.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-lifetime default network observation; Wi-Fi association alone is insufficient. */
class DownloadNetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var defaultNetwork = connectivity?.activeNetwork
    private val _available = MutableStateFlow(isValidated(defaultNetwork?.let { connectivity?.getNetworkCapabilities(it) }))
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
            // Wait for capabilities instead of racing a synchronous query in this callback.
            _available.value = false
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == defaultNetwork) _available.value = isValidated(capabilities)
        }

        override fun onLost(network: Network) {
            if (network == defaultNetwork) {
                defaultNetwork = null
                _available.value = false
            }
        }
    }

    init {
        try { connectivity?.registerDefaultNetworkCallback(callback) }
        catch (error: RuntimeException) { Log.w("DownloadNetworkMonitor", "Unable to observe default network", error) }
    }

    private fun isValidated(capabilities: NetworkCapabilities?): Boolean =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
