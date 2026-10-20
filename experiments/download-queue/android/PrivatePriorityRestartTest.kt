package com.changyow.mediadler.qa

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.core.model.*
import com.changyow.mediadler.data.queue.SqliteDownloadQueueStore
import com.changyow.mediadler.download.DownloadNowResult
import com.changyow.mediadler.download.DownloadQueue
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Two opt-in process-separated phases. Never accesses the live AppContainer queue. */
@RunWith(AndroidJUnit4::class)
class PrivatePriorityRestartTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun productionSqlitePriorityAcrossProcesses() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("qaPrivateRestart") == "true")
        val session = requireNotNull(args.getString("qaPrivateSession"))
        require(session.matches(Regex("[0-9a-f]{8}-[0-9a-f-]{27}")))
        val stage = requireNotNull(args.getString("qaPrivateStage"))
        require(stage in listOf("prepare", "verify"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "qa_private_priority_$session").canonicalFile
        check(root.parentFile == context.filesDir.canonicalFile)
        val exports = File(requireNotNull(context.getExternalFilesDir(null)), "qa_private_priority_$session")
        if (stage == "prepare") {
            check(!root.exists() && !exports.exists()) { "Refuse to overwrite any fixture" }
            check(root.mkdir() && exports.mkdir())
        } else check(root.isDirectory && exports.isDirectory)
        val fixtureContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File {
                require(name == "download_queue.db")
                return File(root, name).also { check(it.canonicalFile.parentFile == root) }
            }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        }
        check(fixtureContext.applicationContext === fixtureContext)
        val store = SqliteDownloadQueueStore(fixtureContext)
        // Verify the production adapter's actual connection before any queue mutation.
        val actualDatabase = File(helper(store).writableDatabase.path).canonicalFile
        check(actualDatabase == fixtureContext.getDatabasePath("download_queue.db").canonicalFile)
        val queue = DownloadQueue(store, File(root, "scratch"))
        check(fixtureContext.getDatabasePath("download_queue.db").isFile)
        val request = request()
        if (stage == "prepare") {
            check(store.read().isEmpty())
            val ids = queue.submitRequests(List(4) { request })
            val active = requireNotNull(queue.claimNext())
            check(active.id == ids[0])
            queue.resolved(active, request)
            queue.setProgress(active, .3f)
            val partial = File(queue.workDirectory(active.id), "qa-checkpoint.part")
            partial.parentFile!!.mkdirs()
            partial.writeText("priority checkpoint retained")
            check(queue.pauseTask(ids[2]))
            check(queue.downloadNow(ids[3]) == DownloadNowResult.PREEMPTING)
            val order = listOf(ids[3], ids[0], ids[1], ids[2])
            check(queue.tasks.value.map { it.id } == order)
            check(queue.get(active.id)?.interruption == ItemInterruption.PRIORITY_REQUEUE)
            check(queue.claimNext() == null)
            val state = JSONObject().put("pid", Process.myPid()).put("time", System.currentTimeMillis())
                .put("ids", JSONArray(ids)).put("order", JSONArray(order)).put("attempt", active.attempt)
                .put("records", JSONArray(json.encodeToString(store.read())))
                .put("database", actualDatabase.path)
                .put("isolation", "Private fixture context; no live queue/service/network/native writer")
            File(root, "expected.json").writeText(state.toString(2))
            File(exports, "prepare.json").writeText(state.toString(2))
            closeStore(store)
        } else {
            val expected = JSONObject(File(root, "expected.json").readText())
            check(expected.getInt("pid") != Process.myPid()) { "Actual target process recreation required" }
            val ids = expected.getJSONArray("ids").let { a -> List(a.length()) { a.getString(it) } }
            val order = expected.getJSONArray("order").let { a -> List(a.length()) { a.getString(it) } }
            val reopened = store.read()
            check(queue.tasks.value.map { it.id } == order)
            check(reopened.map { it.task.queuePosition }.distinct().size == 4)
            check(queue.get(ids[0])?.status == DownloadStatus.QUEUED)
            check(queue.get(ids[0])?.interruption == null)
            check(queue.get(ids[0])?.progress == .3f)
            check(queue.get(ids[2])?.status == DownloadStatus.PAUSED)
            val partial = File(queue.workDirectory(ids[0]), "qa-checkpoint.part")
            check(partial.readText() == "priority checkpoint retained")
            val chosen = requireNotNull(queue.claimNext(preferResolved = false))
            check(chosen.id == ids[3])
            check(queue.complete(chosen, "content://qa-private/chosen", "video/mp4"))
            val resumed = requireNotNull(queue.claimNext(preferResolved = false))
            check(resumed.id == ids[0])
            check(resumed.resolvedRequest == request)
            check(resumed.attempt > expected.getInt("attempt"))
            check(partial.readText() == "priority checkpoint retained")
            check(queue.complete(resumed, "content://qa-private/resumed", "video/mp4"))
            val remaining = requireNotNull(queue.claimNext())
            check(remaining.id == ids[1])
            check(queue.complete(remaining, "content://qa-private/remaining", "video/mp4"))
            check(queue.claimNext() == null)
            check(queue.get(ids[2])?.status == DownloadStatus.PAUSED)
            val result = JSONObject().put("status", "PASS").put("pid", Process.myPid())
                .put("previousPid", expected.getInt("pid")).put("time", System.currentTimeMillis())
                .put("restoredRecords", JSONArray(json.encodeToString(reopened)))
                .put("expectedOrder", JSONArray(order)).put("claimOrder", JSONArray(listOf(chosen.id, resumed.id, remaining.id)))
                .put("manualPausedId", ids[2]).put("checkpointAndSourceRetained", true)
                .put("scope", "Production queue and Android SQLite across two actual target processes in an isolated fixture; live AppContainer untouched")
            closeStore(store)
            check(root.deleteRecursively()) { "Private fixture cleanup incomplete" }
            result.put("privateFixtureCleanup", "COMPLETE")
            File(exports, "verify.json").writeText(result.toString(2))
        }
    }

    private fun closeStore(store: SqliteDownloadQueueStore) {
        helper(store).close()
    }

    private fun helper(store: SqliteDownloadQueueStore): SQLiteOpenHelper {
        val field = SqliteDownloadQueueStore::class.java.getDeclaredField("helper").apply { isAccessible = true }
        return field.get(store) as SQLiteOpenHelper
    }

    private fun request(): DownloadRequest {
        val url = "https://example.invalid/qa-private-priority.mp4"
        val format = MediaFormat("qa-private", "mp4", "QA private fixture", 180, true, true, false, null, MediaResource(url))
        return DownloadRequest(MediaItem(url, null, "qa-private-priority", "QA private priority", null, 72, false,
            listOf(format), SourceContext(url, url), DownloadTarget.DIRECT), FormatSelection.SpecificFormat(format))
    }
}
