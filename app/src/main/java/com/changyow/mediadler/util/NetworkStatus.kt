package com.changyow.mediadler.util

import android.content.Context
import android.net.ConnectivityManager

/** Tiny helper around the connectivity state we gate large downloads on. */
object NetworkStatus {
    /**
     * True when the active network is metered (mobile data, or a Wi-Fi the user flagged as metered).
     * We don't hard-block on this — callers use it to ask for confirmation before a big download.
     */
    fun isMetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        cm.isActiveNetworkMetered
    }.getOrDefault(false)
}
