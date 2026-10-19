package com.changyow.mediadler.sharing

import android.content.SharedPreferences
import android.util.Log
import com.changyow.mediadler.core.sharing.SharingIdleTimeout
import com.changyow.mediadler.core.sharing.SharingSessionSnapshot

/** local_sharing.xml is excluded from both cloud backup and device transfer. */
internal class SharingSessionStore(private val prefs: SharedPreferences) {
    fun read(): SharingSessionSnapshot? {
        if (!prefs.contains(BOOT)) return null
        return try {
            val timeout = SharingIdleTimeout.entries.firstOrNull { it.minutes == prefs.getInt(POLICY, -1) }
                ?: error("Invalid sharing idle policy")
            SharingSessionSnapshot(
                prefs.getBoolean(UPNP, false), prefs.getBoolean(SFTP, false),
                prefs.getLong(REVISION, -1), timeout, prefs.getInt(BOOT, -1),
                prefs.getLong(READ, -1), prefs.getLong(UPPER_BOUND, -1),
            )
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Discarding invalid sharing session", failure)
            write(null, SharingIdleTimeout.DEFAULT)
            null
        }
    }

    /** A single synchronous editor transaction orders stop/reset against read checkpoints. */
    fun write(snapshot: SharingSessionSnapshot?, timeout: SharingIdleTimeout): Boolean {
        val edit = prefs.edit()
        edit.putInt(POLICY, timeout.minutes)
        if (snapshot == null) {
            listOf(UPNP, SFTP, REVISION, BOOT, READ, UPPER_BOUND).forEach(edit::remove)
        } else {
            edit.putBoolean(UPNP, snapshot.upnpEnabled).putBoolean(SFTP, snapshot.sftpEnabled)
                .putLong(REVISION, snapshot.sessionRevision).putInt(BOOT, snapshot.bootCount)
                .putLong(READ, snapshot.lastReadElapsedMillis)
                .putLong(UPPER_BOUND, snapshot.recoveryReadUpperBoundElapsedMillis)
        }
        return edit.commit()
    }

    companion object {
        const val POLICY = "idle_minutes"
        private const val UPNP = "session_upnp"
        private const val SFTP = "session_sftp"
        private const val REVISION = "session_revision"
        private const val BOOT = "session_boot_count"
        private const val READ = "session_last_read_elapsed"
        private const val UPPER_BOUND = "session_read_upper_bound"
        private const val TAG = "SharingSessionStore"
    }
}
