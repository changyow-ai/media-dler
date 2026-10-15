package com.changyow.mediadler.core.download

import com.changyow.mediadler.core.model.QueuePause
import com.changyow.mediadler.core.model.QueuePauseKind
import java.io.IOException

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

    fun message(error: Throwable): String = pauseFor(error)?.message ?: error.message?.take(500)?.takeIf { it.isNotBlank() } ?: "下載失敗，請重試"
}
