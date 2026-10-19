package com.changyow.mediadler.sharing

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.changyow.mediadler.core.sharing.SharingIdleTimeout
import com.changyow.mediadler.core.sharing.SharingSessionSnapshot
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
    val idleTimeout: SharingIdleTimeout = SharingIdleTimeout.DEFAULT,
    val sessionRevision: Long = 0,
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

class SharingController(context: Context, private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime) {
    private val prefs = context.getSharedPreferences("local_sharing", Context.MODE_PRIVATE)
    private val sessions = SharingSessionStore(prefs)
    private val sessionLock = Any()
    private val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private var checkpoint: SharingSessionSnapshot? = null
    private var lastActivityMillis = elapsedRealtime()
    private var awaitingFirstRecoveredRead = false
    private var recoverySuppressed = false
    private val mutable = MutableStateFlow(initialState())
    val state = mutable.asStateFlow()
    val deviceId: UUID = prefs.getString("device_id", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: UUID.randomUUID().also { prefs.edit().putString("device_id", it.toString()).apply() }

    private fun initialState(): SharingState {
        val initial = SharingState(
            username = prefs.getString("username", "media") ?: "media", port = prefs.getInt("port", 2222),
            hasPassword = prefs.contains("hash"),
            idleTimeout = SharingIdleTimeout.fromMinutes(prefs.getInt(SharingSessionStore.POLICY, SharingIdleTimeout.DEFAULT.minutes)),
        )
        val saved = sessions.read() ?: return initial
        val restored = saved.restoredActivityMillis(elapsedRealtime(), bootCount)
        if (restored == null) {
            sessions.write(null, initial.idleTimeout)
            Log.i(TAG, "Discarded expired or invalid sharing recovery session")
            return initial
        }
        val valid = saved.copy(sftpEnabled = saved.sftpEnabled && initial.hasPassword)
        if (!valid.upnpEnabled && !valid.sftpEnabled) {
            sessions.write(null, initial.idleTimeout)
            return initial
        }
        if (valid != saved) sessions.write(valid, valid.idleTimeout)
        checkpoint = valid
        lastActivityMillis = restored
        awaitingFirstRecoveredRead = true
        Log.i(TAG, "Restoring sharing session ${valid.sessionRevision}")
        return initial.copy(
            upnpEnabled = valid.upnpEnabled, sftpEnabled = valid.sftpEnabled,
            upnpStatus = if (valid.upnpEnabled) "正在恢復分享…" else "已停止",
            sftpStatus = if (valid.sftpEnabled) "正在恢復分享…" else "已停止",
            idleTimeout = valid.idleTimeout, sessionRevision = valid.sessionRevision,
        )
    }

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
        synchronized(sessionLock) {
            mutable.update { it.copy(username = username, port = port, hasPassword = true, credentialRevision = it.credentialRevision + 1) }
        }
        if (state.value.sftpEnabled) requestService(context)
    }

    fun setEnabled(context: Context, upnp: Boolean, enabled: Boolean) {
        synchronized(sessionLock) {
            val current = state.value
            if ((if (upnp) current.upnpEnabled else current.sftpEnabled) == enabled) return
            if (!upnp && enabled && !current.hasPassword) {
                mutable.value = current.copy(sftpStatus = "請先設定 SFTP 帳號與密碼")
                return
            }
            val revision = current.sessionRevision + if (enabled) 1 else 0
            val next = if (upnp) current.copy(upnpEnabled = enabled, upnpStatus = if (enabled) "啟動中…" else "停止中…", sessionRevision = revision)
            else current.copy(sftpEnabled = enabled, sftpStatus = if (enabled) "啟動中…" else "停止中…", sessionRevision = revision)
            if (!persist(next, reset = enabled)) {
                if (enabled) {
                    mutable.value = if (upnp) current.copy(upnpStatus = "無法儲存分享狀態") else current.copy(sftpStatus = "無法儲存分享狀態")
                    return
                }
                mutable.value = if (upnp) next.copy(upnpStatus = "已停止；無法儲存停止狀態") else next.copy(sftpStatus = "已停止；無法儲存停止狀態")
            } else {
                mutable.value = next
            }
        }
        requestService(context)
    }

    fun setIdleTimeout(context: Context, timeout: SharingIdleTimeout) {
        synchronized(sessionLock) {
            val current = state.value
            if (current.idleTimeout == timeout) return
            val next = current.copy(idleTimeout = timeout, sessionRevision = current.sessionRevision + 1)
            if (!persist(next, reset = true)) return
            mutable.value = next
        }
        if (state.value.let { it.upnpEnabled || it.sftpEnabled }) requestService(context)
    }

    internal fun stopIfIdle(expected: SharingState): Boolean = synchronized(sessionLock) {
        if (state.value != expected) return false
        val message = "閒置 ${expected.idleTimeout.label}，已自動關閉"
        val next = expected.copy(
            upnpEnabled = false, sftpEnabled = false, upnpStatus = message, sftpStatus = message,
        )
        val saved = persist(next)
        mutable.value = if (saved) next else next.copy(
            upnpStatus = "$message；無法儲存停止狀態", sftpStatus = "$message；無法儲存停止狀態",
        )
        if (saved) Log.i(TAG, "Sharing idle deadline reached; recovery cleared")
        else Log.e(TAG, "Sharing stopped at idle deadline, but durable recovery could not be cleared")
        true
    }

    internal fun activityStartMillis(revision: Long): Long? = synchronized(sessionLock) {
        lastActivityMillis.takeIf { state.value.sessionRevision == revision }
    }

    internal fun recordRead(revision: Long, readElapsedMillis: Long) = synchronized(sessionLock) {
        val current = state.value
        if (current.sessionRevision != revision || !current.let { it.upnpEnabled || it.sftpEnabled }) return
        lastActivityMillis = if (awaitingFirstRecoveredRead) readElapsedMillis else maxOf(lastActivityMillis, readElapsedMillis)
        awaitingFirstRecoveredRead = false
        val saved = checkpoint ?: return
        if (saved.shouldCheckpoint(readElapsedMillis)) {
            val next = SharingSessionSnapshot.capture(current.upnpEnabled, current.sftpEnabled, revision,
                current.idleTimeout, bootCount, lastActivityMillis)
            if (sessions.write(next, current.idleTimeout)) checkpoint = next
            else Log.w(TAG, "Unable to checkpoint sharing activity")
        }
    }

    /** Called from a visible Activity; background recovery itself is owned by START_STICKY. */
    fun resume(context: Context) {
        // The service's tracker arbitrates expiry against reads already in flight.
        if (shouldRestart()) requestService(context)
    }

    internal fun shouldRestart(): Boolean = synchronized(sessionLock) {
        !recoverySuppressed && state.value.let { it.upnpEnabled || it.sftpEnabled }
    }

    private fun persist(next: SharingState, reset: Boolean = false): Boolean {
        val active = next.upnpEnabled || next.sftpEnabled
        val now = elapsedRealtime()
        val saved = if (!active) null else if (reset || checkpoint == null) {
            SharingSessionSnapshot.capture(next.upnpEnabled, next.sftpEnabled, next.sessionRevision, next.idleTimeout, bootCount, now)
        } else checkpoint!!.copy(upnpEnabled = next.upnpEnabled, sftpEnabled = next.sftpEnabled,
            sessionRevision = next.sessionRevision, idleTimeout = next.idleTimeout)
        if (!sessions.write(saved, next.idleTimeout) && !sessions.write(saved, next.idleTimeout)) {
            recoverySuppressed = true
            Log.e(TAG, "Unable to persist sharing session")
            return false
        }
        recoverySuppressed = false
        checkpoint = saved
        if (reset) { lastActivityMillis = now; awaitingFirstRecoveredRead = false }
        return true
    }

    private fun requestService(context: Context) {
        try { ContextCompat.startForegroundService(context, Intent(context, SharingService::class.java)) }
        catch (failure: Exception) { stopped("無法啟動分享：${failure.message}") }
    }
    internal fun report(upnp: Boolean, message: String, failed: Boolean = false, expectedRevision: Long? = null) = synchronized(sessionLock) {
        val current = state.value
        if (expectedRevision != null && current.sessionRevision != expectedRevision) return
        val enabled = if (upnp) current.upnpEnabled else current.sftpEnabled
        if (!enabled && message != "已停止") return
        val next = if (upnp) current.copy(upnpStatus = message, upnpEnabled = current.upnpEnabled && !failed)
        else current.copy(sftpStatus = message, sftpEnabled = current.sftpEnabled && !failed)
        val saved = !failed || persist(next)
        mutable.value = if (saved) next else if (upnp) next.copy(upnpStatus = "$message；無法儲存停止狀態")
        else next.copy(sftpStatus = "$message；無法儲存停止狀態")
    }
    internal fun stopped(message: String = "已停止") = synchronized(sessionLock) {
        val next = state.value.copy(upnpEnabled = false, sftpEnabled = false, upnpStatus = message, sftpStatus = message)
        mutable.value = if (persist(next)) next else next.copy(
            upnpStatus = "$message；無法儲存停止狀態", sftpStatus = "$message；無法儲存停止狀態",
        )
    }
    internal fun serviceDestroyed() = synchronized(sessionLock) {
        val current = state.value
        mutable.value = current.copy(
            upnpStatus = if (current.upnpEnabled) "等待系統恢復分享…" else if (current.upnpStatus == "停止中…") "已停止" else current.upnpStatus,
            sftpStatus = if (current.sftpEnabled) "等待系統恢復分享…" else if (current.sftpStatus == "停止中…") "已停止" else current.sftpStatus,
        )
    }

    private companion object { const val TAG = "SharingController" }
}
