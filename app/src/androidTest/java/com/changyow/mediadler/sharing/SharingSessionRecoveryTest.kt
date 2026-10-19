package com.changyow.mediadler.sharing

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.core.sharing.SharingIdleTimeout
import com.changyow.mediadler.core.sharing.SharingSessionSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real preference transactions, isolated from the application's session, credentials and services. */
@RunWith(AndroidJUnit4::class)
class SharingSessionRecoveryTest {
    private lateinit var context: IsolatedContext
    private lateinit var store: SharingSessionStore
    private var boot = -1
    private var now = 7_200_000L

    @Before fun prepare() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedContext(app)
        store = SharingSessionStore(context.getSharedPreferences("local_sharing", Context.MODE_PRIVATE))
        boot = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        assertTrue("This device must expose its boot count", boot >= 0)
    }

    @After fun cleanup() { context.deleteOwnPreferences() }

    @Test fun recreationRestoresOnlyDesiredProtocolWithoutExtendingDeadline() {
        val saved = snapshot()
        assertTrue(store.write(saved, saved.idleTimeout))
        repeat(3) {
            val controller = controller()
            assertTrue(controller.state.value.upnpEnabled)
            assertFalse(controller.state.value.sftpEnabled)
            assertEquals(saved.recoveryReadUpperBoundElapsedMillis, controller.activityStartMillis(saved.sessionRevision))
            assertEquals(saved, store.read())
        }
    }

    @Test fun positiveReadCheckpointsButServiceTeardownDoesNotClearDesire() {
        val saved = snapshot()
        assertTrue(store.write(saved, saved.idleTimeout))
        val controller = controller()
        val read = now
        controller.recordRead(saved.sessionRevision, read)
        assertEquals(read, store.read()!!.lastReadElapsedMillis)
        controller.serviceDestroyed()
        assertTrue(controller.state.value.upnpEnabled)
        assertTrue(controller().state.value.upnpEnabled)
    }

    @Test fun stopAndLateCheckpointCannotResurrectSession() {
        val saved = snapshot()
        assertTrue(store.write(saved, saved.idleTimeout))
        val controller = controller()
        controller.stopped()
        controller.recordRead(saved.sessionRevision, now)
        assertNull(store.read())
        val restored = controller()
        restored.resume(context)
        assertFalse(restored.state.value.upnpEnabled)
        assertEquals(0, context.serviceStarts)
    }

    @Test fun visibleResumeLeavesLiveExpiryToTheServiceReadGuard() {
        val saved = snapshot()
        assertTrue(store.write(saved, saved.idleTimeout))
        val controller = controller()
        now += SharingIdleTimeout.ONE_HOUR.millis
        controller.resume(context)
        assertTrue(controller.state.value.upnpEnabled)
        assertEquals(1, context.serviceStarts)
        assertNotNull(store.read())
    }

    @Test fun failedStopCommitIsReportedAndSuppressesCurrentProcessRecovery() {
        val saved = snapshot()
        assertTrue(store.write(saved, saved.idleTimeout))
        val controller = controller()
        val attempts = context.commitAttempts
        context.failCommits = true
        controller.stopped()
        assertEquals(attempts + 2, context.commitAttempts)
        assertFalse(controller.state.value.upnpEnabled)
        assertTrue(controller.state.value.upnpStatus.contains("無法儲存停止狀態"))
        assertFalse(controller.shouldRestart())
        controller.resume(context)
        assertEquals(0, context.serviceStarts)
        // Failure is explicitly not a claim that the old disk record was cleared.
        assertNotNull(store.read())
        context.failCommits = false
        controller.setEnabled(context, upnp = true, enabled = true)
        assertTrue(controller.shouldRestart())
    }

    @Test fun staleReadCannotOverwriteReenabledSession() {
        val saved = snapshot()
        assertTrue(store.write(saved, saved.idleTimeout))
        val controller = controller()
        controller.setEnabled(context, upnp = true, enabled = false)
        controller.setEnabled(context, upnp = true, enabled = true)
        val fresh = store.read()!!
        assertNotEquals(saved.sessionRevision, fresh.sessionRevision)
        controller.recordRead(saved.sessionRevision, now)
        assertEquals(fresh, store.read())
    }

    @Test fun expiredOrOtherBootIsClearedBeforeAnyServiceStart() {
        val expired = snapshot(lastRead = now - SharingIdleTimeout.THIRTY_MINUTES.millis - 120_000)
        for (invalid in listOf(expired, snapshot().copy(bootCount = boot + 1), snapshot().copy(lastReadElapsedMillis = now + 120_000))) {
            assertTrue(store.write(invalid, invalid.idleTimeout))
            val controller = controller()
            controller.resume(context)
            assertFalse(controller.state.value.upnpEnabled)
            assertNull(store.read())
        }
        assertEquals(0, context.serviceStarts)
    }

    @Test fun missingSftpCredentialsDoesNotEnableItDuringRecovery() {
        val saved = snapshot().copy(sftpEnabled = true)
        assertTrue(store.write(saved, saved.idleTimeout))
        val controller = controller()
        assertTrue(controller.state.value.upnpEnabled)
        assertFalse(controller.state.value.sftpEnabled)
        assertFalse(store.read()!!.sftpEnabled)
    }

    @Test fun idleStopAndPolicyChangeStayDurable() {
        val saved = snapshot()
        assertTrue(store.write(saved, saved.idleTimeout))
        val controller = controller()
        controller.setIdleTimeout(context, SharingIdleTimeout.TWO_HOURS)
        assertEquals(SharingIdleTimeout.TWO_HOURS, controller().state.value.idleTimeout)
        assertFalse(controller.stopIfIdle(savedState(saved)))
        assertTrue(controller.stopIfIdle(controller.state.value))
        assertNull(store.read())
        assertFalse(controller().state.value.upnpEnabled)
        assertEquals(SharingIdleTimeout.TWO_HOURS, controller().state.value.idleTimeout)
    }

    private fun controller() = SharingController(context) { now }

    private fun snapshot(lastRead: Long = now - 90_000) = SharingSessionSnapshot.capture(
        true, false, 7, SharingIdleTimeout.THIRTY_MINUTES, boot, lastRead,
    )

    private fun savedState(saved: SharingSessionSnapshot) = SharingState(
        upnpEnabled = saved.upnpEnabled, sftpEnabled = saved.sftpEnabled,
        idleTimeout = saved.idleTimeout, sessionRevision = saved.sessionRevision,
    )

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "sharing-recovery-test-${UUID.randomUUID()}-"
        var serviceStarts = 0
        var failCommits = false
        var commitAttempts = 0
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val actual = baseContext.getSharedPreferences(prefix + name, mode)
            return object : SharedPreferences by actual {
                override fun edit(): SharedPreferences.Editor {
                    val editor = actual.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit(): Boolean {
                            commitAttempts++
                            return !failCommits && editor.commit()
                        }
                    }
                }
            }
        }
        override fun startForegroundService(service: Intent): ComponentName {
            serviceStarts++
            return ComponentName(packageName, SharingService::class.java.name)
        }
        fun deleteOwnPreferences() { baseContext.deleteSharedPreferences(prefix + "local_sharing") }
    }
}
