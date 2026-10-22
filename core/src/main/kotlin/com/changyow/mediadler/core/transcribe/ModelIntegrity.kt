package com.changyow.mediadler.core.transcribe

import java.io.File
import java.security.MessageDigest

/** Streaming checks for pinned model deployments. The digest is never computed on the UI thread. */
object ModelIntegrity {
    fun verify(file: File, expectedBytes: Long, expectedSha256: String) {
        check(file.isFile && file.length() == expectedBytes) { "模型大小不符：${file.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == expectedSha256.lowercase()) { "模型 SHA-256 驗證失敗：${file.name}" }
    }
}
