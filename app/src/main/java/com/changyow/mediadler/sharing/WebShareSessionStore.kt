package com.changyow.mediadler.sharing

import android.content.SharedPreferences
import com.changyow.mediadler.core.sharing.WebShareSession

/** A process restart restores the same deadline, never a new sharing window. */
internal class WebShareSessionStore(private val prefs: SharedPreferences) {
    fun read(nowElapsedMillis: Long, bootCount: Int): WebShareSession? {
        if (!prefs.contains(STARTED)) return null
        val session = try {
            WebShareSession(prefs.getLong(STARTED, -1),
                prefs.getLong(DEADLINE, -1), prefs.getInt(BOOT, -1))
                .takeIf { it.isValid(nowElapsedMillis, bootCount) }
        } catch (_: RuntimeException) { null }
        if (session == null) write(null)
        return session
    }

    fun write(session: WebShareSession?): Boolean {
        val edit = prefs.edit()
        if (session == null) listOf(STARTED, DEADLINE, BOOT).forEach(edit::remove)
        else edit.putLong(STARTED, session.startedElapsedMillis)
            .putLong(DEADLINE, session.expiresAtElapsedMillis).putInt(BOOT, session.bootCount)
        return edit.commit()
    }

    private companion object {
        const val STARTED = "web_started_elapsed"
        const val DEADLINE = "web_deadline_elapsed"
        const val BOOT = "web_boot_count"
    }
}
