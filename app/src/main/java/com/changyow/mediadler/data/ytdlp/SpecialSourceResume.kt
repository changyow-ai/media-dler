package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.download.HlsResumeGuard
import com.changyow.mediadler.core.model.MediaResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Runs only for the claimed special-source job, never for queued episodes. */
internal object SpecialSourceResume {
    suspend fun identity(resource: MediaResource): String {
        val context = currentCoroutineContext()
        val fingerprint = try {
            runInterruptible {
                var url = URL(resource.url)
                repeat(4) {
                    context.ensureActive()
                    require(url.protocol in setOf("http", "https"))
                    val connection = url.openConnection() as HttpURLConnection
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 7_000
                        connection.readTimeout = 7_000
                        resource.httpHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                        when (connection.responseCode) {
                            301, 302, 303, 307, 308 -> {
                                url = URL(url, connection.getHeaderField("Location") ?: return@runInterruptible null)
                            }
                            200 -> {
                                val bytes = connection.inputStream.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        context.ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (output.size() + count > 1024 * 1024) return@runInterruptible null
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                }
                                return@runInterruptible HlsResumeGuard.fingerprint(bytes.toString(Charsets.UTF_8))
                            }
                            else -> return@runInterruptible null
                        }
                    } finally { connection.disconnect() }
                }
                null
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { context.ensureActive(); null }
        // Master/live/encrypted/unreadable manifests cannot prove compatible fragments.
        // Retain old attempts for recovery/deletion, but start a separate scratch namespace.
        return fingerprint ?: UUID.randomUUID().toString()
    }
}
