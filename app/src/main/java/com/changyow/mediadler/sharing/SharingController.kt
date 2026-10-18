package com.changyow.mediadler.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class SharingState(
    val upnpEnabled: Boolean = false,
    val sftpEnabled: Boolean = false,
    val upnpStatus: String = "已停止",
    val sftpStatus: String = "已停止",
    val username: String = "media",
    val port: Int = 2222,
    val hasPassword: Boolean = false,
    val credentialRevision: Int = 0,
)

internal data class SftpCredentials(val username: String, val salt: ByteArray, val hash: ByteArray) {
    fun accepts(user: String, password: String): Boolean {
        if (user != username || password.length > 256) return false
        return MessageDigest.isEqual(hash, passwordHash(password, salt))
    }
}

private fun passwordHash(password: String, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, 180_000, 256)
    return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
    finally { spec.clearPassword() }
}

class SharingController(context: Context) {
    private val prefs = context.getSharedPreferences("local_sharing", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(SharingState(
        username = prefs.getString("username", "media") ?: "media", port = prefs.getInt("port", 2222),
        hasPassword = prefs.contains("hash"),
    ))
    val state = mutable.asStateFlow()
    val deviceId: UUID = prefs.getString("device_id", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: UUID.randomUUID().also { prefs.edit().putString("device_id", it.toString()).apply() }

    internal fun credentials(): SftpCredentials? = runCatching {
        val decode = Base64.getDecoder()
        SftpCredentials(state.value.username, decode.decode(prefs.getString("salt", null) ?: return null),
            decode.decode(prefs.getString("hash", null) ?: return null))
    }.getOrNull()

    suspend fun saveLogin(context: Context, username: String, password: String, port: Int) = withContext(Dispatchers.IO) {
        require(username.matches(Regex("[A-Za-z0-9._-]{1,32}"))) { "帳號限 1～32 個英數字、點、底線或連字號" }
        require(port in 1024..65535) { "連接埠需介於 1024～65535" }
        require(password.length in 8..256 || password.isEmpty() && state.value.hasPassword) { "密碼需為 8～256 個字元" }
        val edit = prefs.edit().putString("username", username).putInt("port", port)
        if (password.isNotEmpty()) {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val encode = Base64.getEncoder()
            edit.putString("salt", encode.encodeToString(salt)).putString("hash", encode.encodeToString(passwordHash(password, salt)))
        }
        check(edit.commit()) { "無法儲存登入設定" }
        mutable.update { it.copy(username = username, port = port, hasPassword = true, credentialRevision = it.credentialRevision + 1) }
        if (state.value.sftpEnabled) requestService(context)
    }

    fun setEnabled(context: Context, upnp: Boolean, enabled: Boolean) {
        if ((if (upnp) state.value.upnpEnabled else state.value.sftpEnabled) == enabled) return
        if (!upnp && enabled && !state.value.hasPassword) {
            mutable.update { it.copy(sftpStatus = "請先設定 SFTP 帳號與密碼") }
            return
        }
        mutable.update {
            if (upnp) it.copy(upnpEnabled = enabled, upnpStatus = if (enabled) "啟動中…" else "停止中…")
            else it.copy(sftpEnabled = enabled, sftpStatus = if (enabled) "啟動中…" else "停止中…")
        }
        requestService(context)
    }

    private fun requestService(context: Context) {
        try { ContextCompat.startForegroundService(context, Intent(context, SharingService::class.java)) }
        catch (failure: Exception) { stopped("無法啟動分享：${failure.message}") }
    }
    internal fun report(upnp: Boolean, message: String, failed: Boolean = false) = mutable.update {
        val enabled = if (upnp) it.upnpEnabled else it.sftpEnabled
        if (!failed && !enabled && message != "已停止") return@update it
        if (upnp) it.copy(upnpStatus = message, upnpEnabled = it.upnpEnabled && !failed)
        else it.copy(sftpStatus = message, sftpEnabled = it.sftpEnabled && !failed)
    }
    internal fun stopped(message: String = "已停止") = mutable.update {
        it.copy(upnpEnabled = false, sftpEnabled = false, upnpStatus = message, sftpStatus = message)
    }
    internal fun serviceDestroyed() = mutable.update {
        it.copy(upnpEnabled = false, sftpEnabled = false,
            upnpStatus = if (it.upnpEnabled || it.upnpStatus == "停止中…") "已停止" else it.upnpStatus,
            sftpStatus = if (it.sftpEnabled || it.sftpStatus == "停止中…") "已停止" else it.sftpStatus)
    }
}
