package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.download.HlsResumeGuard
import com.changyow.mediadler.core.model.MediaResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

internal data class PreparedHlsResume(
    val resource: MediaResource,
    val manifest: String,
    val identity: String,
    val skippedAds: Int = 0,
    val skippedAdSegments: Int = 0,
    val skippedAdSeconds: Double = 0.0,
)

/** Runs only for the claimed special-source job, never for queued episodes. */
internal object SpecialSourceResume {
    suspend fun prepare(resource: MediaResource, skipEightMapleAds: Boolean = false): PreparedHlsResume? {
        val context = currentCoroutineContext()
        return try {
            runInterruptible {
                fun fetch(source: String): Pair<URL, ByteArray>? {
                    var url = URL(source)
                    repeat(4) {
                        context.ensureActive()
                        require(url.protocol in setOf("http", "https") && url.userInfo == null && url.host.isNotEmpty())
                        val connection = url.openConnection() as HttpURLConnection
                        try {
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 7_000
                            connection.readTimeout = 7_000
                            resource.httpHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                            when (connection.responseCode) {
                                301, 302, 303, 307, 308 -> {
                                    url = URL(url, connection.getHeaderField("Location") ?: return null)
                                }
                                200 -> {
                                    val bytes = connection.inputStream.use { input ->
                                        val output = ByteArrayOutputStream()
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            context.ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            if (output.size() + count > 1024 * 1024) return null
                                            output.write(buffer, 0, count)
                                        }
                                        output.toByteArray()
                                    }
                                    return url to bytes
                                }
                                else -> return null
                            }
                        } finally { connection.disconnect() }
                    }
                    return null
                }
                fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()

                var (url, bytes) = fetch(resource.url) ?: return@runInterruptible null
                var manifest = decode(bytes)
                if (HlsResumeGuard.fingerprint(manifest) == null) {
                    val child = HlsResumeGuard.singleVariantUrl(manifest, url.toExternalForm()) ?: return@runInterruptible null
                    val fetched = fetch(child) ?: return@runInterruptible null
                    url = fetched.first
                    bytes = fetched.second
                    manifest = decode(bytes)
                    if (HlsResumeGuard.fingerprint(manifest) == null) return@runInterruptible null
                }
                val filtered = if (skipEightMapleAds) EightMapleHlsAds.filter(manifest)
                    else EightMapleHlsAds.Result(manifest)
                val digest = MessageDigest.getInstance("SHA-256")
                fun add(value: ByteArray) {
                    digest.update(ByteBuffer.allocate(4).putInt(value.size).array())
                    digest.update(value)
                }
                add(url.toExternalForm().toByteArray(Charsets.UTF_8))
                add(bytes)
                // HttpURLConnection treats header names case-insensitively; later values override earlier ones.
                val headers = linkedMapOf<String, String>()
                resource.httpHeaders.forEach { (name, value) -> headers[name.lowercase(Locale.ROOT)] = value }
                headers.toSortedMap().forEach { (name, value) ->
                    add(name.toByteArray(Charsets.UTF_8))
                    add(value.toByteArray(Charsets.UTF_8))
                }
                if (filtered.skippedAds > 0) {
                    add(EightMapleHlsAds.IDENTITY_VERSION.toByteArray(Charsets.UTF_8))
                    add(filtered.manifest.toByteArray(Charsets.UTF_8))
                }
                PreparedHlsResume(resource.copy(url = url.toExternalForm()), filtered.manifest,
                    digest.digest().joinToString("") { "%02x".format(it) },
                    filtered.skippedAds, filtered.skippedAdSegments, filtered.skippedAdSeconds)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { context.ensureActive(); null }
    }
}
