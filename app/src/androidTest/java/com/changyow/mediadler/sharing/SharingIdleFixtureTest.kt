package com.changyow.mediadler.sharing

import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.appContainer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Opt-in fixture for independent real-time idle QA, ONLY on a new disposable emulator.
 * -e disposableIdleFixture prepare|cleanup
 * Login: idleqa / idle-test-password, port22222. Does not start sharing or alter its timer.
 */
@RunWith(AndroidJUnit4::class)
class SharingIdleFixtureTest {
    @Test fun fixture() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val action = InstrumentationRegistry.getArguments().getString("disposableIdleFixture")
        assumeTrue("Disposable-emulator fixture is opt-in", action in listOf("prepare", "cleanup"))
        val context = instrumentation.targetContext
        val controller = context.appContainer.sharingController
        assertFalse(controller.state.value.upnpEnabled)
        assertFalse(controller.state.value.sftpEnabled)
        val prefs = context.getSharedPreferences("idle_qa_fixture", 0)
        if (action == "cleanup") {
            prefs.getString("uri", null)?.let { context.contentResolver.delete(android.net.Uri.parse(it), null, null) }
            prefs.edit().clear().commit()
            return@runBlocking
        }
        check(!controller.state.value.hasPassword && !prefs.contains("uri")) { "Use a fresh disposable emulator" }
        val folder = "sharing-idle-qa-${UUID.randomUUID()}"
        val filename = "idle-fixture.bin"
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/media-dler/$folder/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        check(prefs.edit().putString("uri", uri.toString()).commit())
        context.contentResolver.openOutputStream(uri)!!.use { output ->
            output.write(ByteArray(512 * 1024) { (it % 251).toByte() })
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        controller.saveLogin(context, "idleqa", "idle-test-password", 22222)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nIDLE_QA_FIXTURE " + JSONObject().put("path", "/$folder/$filename")
                .put("size", 512 * 1024).put("port", 22222).toString() + "\n")
        })
    }
}
