package com.changyow.mediadler.core.download

import java.io.IOException
import kotlinx.coroutines.delay

/** A network interruption retries the same validated HLS snapshot and checkpoint. */
object HlsNetworkRetry {
    suspend fun execute(onRetry: (Int, Long) -> Unit, download: suspend () -> Unit) {
        var retries = 0
        while (true) {
            try {
                download()
                return
            } catch (error: IOException) {
                if (retries >= delays.size || !DownloadFailurePolicy.isTransientNetworkFailure(error)) throw error
                val wait = delays[retries++]
                onRetry(retries, wait)
                delay(wait)
            }
        }
    }

    private val delays = longArrayOf(5_000, 15_000, 30_000, 60_000)
}
