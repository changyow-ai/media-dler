package com.changyow.mediadler.data.queue

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.core.model.AdRemovalPolicy
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.QueuedSpec
import com.changyow.mediadler.core.model.SourceKind
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Release checks for optimized Kotlin libraries; never reads or changes the real queue. */
@RunWith(AndroidJUnit4::class)
class R8KotlinRuntimeTest {
    @Test fun frozenQueueJsonSurvivesProductionStoreReadWriteAndReopen() {
        val isolated = IsolatedQueueContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            isolated.openOrCreateDatabase("download_queue.db", Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE download_queue (id TEXT PRIMARY KEY, ordinal INTEGER NOT NULL UNIQUE, payload TEXT NOT NULL)")
                db.execSQL("INSERT INTO download_queue (id, ordinal, payload) VALUES (?, ?, ?)",
                    arrayOf<Any>("r8-frozen", 7L, FROZEN_QUEUE))
                db.version = 1
            }
            val store = SqliteDownloadQueueStore(isolated)
            val restored = store.read().single()
            assertEquals("中文佇列測試", restored.task.title)
            assertEquals(DownloadStatus.PAUSED, restored.task.status)
            assertEquals(7L, restored.task.queuePosition)
            assertEquals(0, restored.attempt)
            assertFalse(restored.deleteRequested)
            assertTrue(restored.task.skipAdAnalysis)
            assertEquals(AdRemovalPolicy.WEIGHTED, restored.adRemovalPolicy)
            val page = restored.spec as QueuedSpec.Page
            assertEquals(SourceKind.EIGHT_MAPLE, page.page.kind)
            assertEquals(page.page.playbackUrl, page.page.originalUrl)
            assertEquals(FormatSelection.CappedVideo(720), page.selection)

            val updated = restored.copy(spec = page.copy(selection = FormatSelection.BestVideo), attempt = 2)
            assertEquals(listOf(updated), store.transact { listOf(updated) })
            assertEquals(listOf(updated), SqliteDownloadQueueStore(isolated).read())
            isolated.openOrCreateDatabase("download_queue.db", Context.MODE_PRIVATE, null).use { db ->
                db.rawQuery("SELECT payload FROM download_queue WHERE id = ?", arrayOf("r8-frozen")).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    val encoded = JSONObject(cursor.getString(0))
                    assertFalse(encoded.has("futureField"))
                    assertEquals(2, encoded.getInt("attempt"))
                    assertEquals("PAUSED", encoded.getJSONObject("task").getString("status"))
                    assertEquals("com.changyow.mediadler.core.model.QueuedSpec.Page",
                        encoded.getJSONObject("spec").getString("type"))
                    assertEquals("com.changyow.mediadler.core.model.FormatSelection.BestVideo",
                        encoded.getJSONObject("spec").getJSONObject("selection").getString("type"))
                }
            }
        } finally {
            isolated.cleanup()
        }
    }

    @Test(timeout = 30_000) fun coroutineDispatchFlowAndCancellationRunOnRelease() = runBlocking {
        withTimeout(20_000) {
            withContext(Dispatchers.Main.immediate) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                val state = MutableStateFlow(0)
                val observed = async { state.first { it == 42 } }
                state.value = withContext(Dispatchers.IO) {
                    assertTrue(Looper.myLooper() != Looper.getMainLooper())
                    42
                }
                assertEquals(42, observed.await())

                val started = CompletableDeferred<Unit>()
                val cleaned = CompletableDeferred<Unit>()
                val child = launch {
                    try {
                        started.complete(Unit)
                        delay(Long.MAX_VALUE)
                    } finally {
                        cleaned.complete(Unit)
                    }
                }
                started.await()
                child.cancel()
                child.join()
                assertTrue(child.isCancelled)
                assertTrue(cleaned.isCompleted)
            }
        }
    }

    private class IsolatedQueueContext(base: Context) : ContextWrapper(base) {
        private val prefix = "r8-kotlin-" + UUID.randomUUID() + "-"
        private val databases = mutableListOf<SQLiteDatabase>()
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix + name).also {
            names += prefix + name
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
            baseContext.openOrCreateDatabase(prefix + name, mode, factory).also {
                names += prefix + name
                databases += it
            }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?) =
            baseContext.openOrCreateDatabase(prefix + name, mode, factory, errorHandler).also {
                names += prefix + name
                databases += it
            }
        fun cleanup() {
            databases.filter { it.isOpen }.forEach { it.close() }
            names.forEach { check(baseContext.deleteDatabase(it)) }
        }
    }

    private companion object {
        // Existing discriminator names, omitted default fields, and a forward-compatible unknown key.
        val FROZEN_QUEUE = """
            {
              "task": {
                "id": "r8-frozen", "title": "中文佇列測試", "sourceUrl": "https://example.invalid/play",
                "thumbnailUrl": null, "formatLabel": "720p", "status": "PAUSED", "progress": 0.25,
                "outputUri": null, "mimeType": null, "errorMessage": null, "createdAt": 1,
                "queuePosition": 7, "skipAdAnalysis": true
              },
              "spec": {
                "type": "com.changyow.mediadler.core.model.QueuedSpec.Page",
                "page": {"playbackUrl": "https://example.invalid/play", "kind": "EIGHT_MAPLE"},
                "selection": {
                  "type": "com.changyow.mediadler.core.model.FormatSelection.CappedVideo", "maxHeight": 720
                }
              },
              "adRemovalPolicy": "WEIGHTED", "futureField": true
            }
        """.trimIndent()
    }
}
