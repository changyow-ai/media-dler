package com.changyow.mediadler.core.download

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
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
                if (retries >= delays.size || !isTransientNetworkFailure(error)) throw error
                val wait = delays[retries++]
                onRetry(retries, wait)
                delay(wait)
            }
        }
    }

    private fun isTransientNetworkFailure(error: IOException): Boolean {
        if (DownloadFailurePolicy.pauseFor(error) != null || DownloadFailurePolicy.shouldRefresh(error)) return false
        val causes = generateSequence<Throwable>(error) { cause -> cause.cause?.takeUnless { it === cause } }.take(10).toList()
        val message = causes.joinToString("\n") { it.message.orEmpty() }.lowercase(Locale.ROOT)
        if (permanentHttp.containsMatchIn(message) || message.contains("permission denied") ||
            message.contains("read-only file system")) return false
        return causes.any { it is UnknownHostException || it is SocketTimeoutException || it is SocketException } ||
            networkMessages.any(message::contains)
    }

    private val delays = longArrayOf(5_000, 15_000, 30_000, 60_000)
    private val permanentHttp = Regex("\\bhttp(?:\\s*error)?\\s*:?\\s*(?:401|403|404|410)\\b")
    private val networkMessages = listOf("no address associated with hostname", "temporary failure in name resolution",
        "name or service not known", "getaddrinfo failed", "network is unreachable", "network unreachable",
        "connection reset", "connection aborted", "connection timed out", "read timed out")
}
