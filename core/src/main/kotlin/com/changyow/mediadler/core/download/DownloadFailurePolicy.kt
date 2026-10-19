package com.changyow.mediadler.core.download

import com.changyow.mediadler.core.model.QueuePause
import com.changyow.mediadler.core.model.QueuePauseKind
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

class LowStorageException : IOException("儲存空間不足，請釋放空間後繼續排程")

object DownloadFailurePolicy {
    private val httpStatus = Regex("""\bHTTP(?:\s*Error)?\s*:?\s*(\d{3})\b""", RegexOption.IGNORE_CASE)
    private fun causes(error: Throwable) = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(10)

    fun pauseFor(error: Throwable): QueuePause? = if (causes(error).any {
        it is LowStorageException || it.javaClass.simpleName == "SQLiteFullException" ||
            it.message.orEmpty().lowercase().let { message ->
                message.contains("no space left on device") || message.contains("enospc") || message.contains("errno 28")
            }
    }) QueuePause(QueuePauseKind.STORAGE, "儲存空間不足，請釋放空間後繼續排程") else null

    /** One page refresh is useful for rejected/expired media URLs; never change the selected line. */
    fun shouldRefresh(error: Throwable): Boolean = causes(error).any {
        httpStatus.find(it.message.orEmpty())?.groupValues?.get(1) in setOf("403", "410")
    }

    val networkPause = QueuePause(QueuePauseKind.NETWORK, "等待網路恢復後繼續下載")

    /** Native engines wrap socket errors in text; permanent HTTP/storage errors take precedence. */
    fun isTransientNetworkFailure(error: Throwable): Boolean {
        if (pauseFor(error) != null) return false
        val chain = causes(error).toList()
        val message = chain.joinToString("\n") { it.message.orEmpty() }.lowercase(Locale.ROOT)
        val statuses = httpStatus.findAll(message).map { it.groupValues[1].toInt() }.toList()
        if (statuses.any { it in 400..499 && it !in setOf(408, 429) } ||
            listOf("permission denied", "read-only file system", "disk quota exceeded", "authentication required",
                "login required", "sign in to confirm").any(message::contains)) return false
        return chain.any { it is UnknownHostException || it is SocketTimeoutException || it is SocketException } ||
            statuses.any { it == 408 || it == 429 || it in 500..599 } || networkMessages.any(message::contains)
    }

    private val networkMessages = listOf("no address associated with hostname", "temporary failure in name resolution",
        "name or service not known", "getaddrinfo failed", "unable to resolve host", "network is unreachable",
        "network unreachable", "network disconnected", "connection reset", "connection aborted", "connection refused",
        "connection timed out", "read timed out", "the read operation timed out", "the handshake operation timed out",
        "failed to connect", "software caused connection abort",
        "remote end closed connection", "remote end closed the connection", "connection closed unexpectedly")

    fun message(error: Throwable): String = pauseFor(error)?.message ?: error.message?.take(500)?.takeIf { it.isNotBlank() } ?: "下載失敗，請重試"

    /** A source failure does not establish that the device is offline or the video was removed. */
    fun sourceFailureMessage(error: Throwable): String {
        pauseFor(error)?.let { return it.message }
        val detail = message(error)
        val statuses = causes(error).flatMap { httpStatus.findAll(it.message.orEmpty()) }
            .map { it.groupValues[1].toInt() }.toSet()
        val text = causes(error).joinToString("\n") { it.message.orEmpty() }.lowercase(Locale.ROOT)
        val explanation = when {
            statuses.any { it == 404 || it == 410 } -> "此來源的影片已不存在或連結已失效，請更換線路。"
            403 in statuses -> "此來源拒絕存取，請稍後重試或更換線路。"
            listOf("certificate verify failed", "self signed certificate", "self-signed certificate",
                "trust anchor for certification path not found").any(text::contains) ->
                "此來源的安全憑證驗證失敗，請更換線路。"
            isTransientNetworkFailure(error) -> "此來源目前無法載入，請稍後重試或更換線路。"
            else -> return detail
        }
        return "$explanation\n$detail"
    }
}
