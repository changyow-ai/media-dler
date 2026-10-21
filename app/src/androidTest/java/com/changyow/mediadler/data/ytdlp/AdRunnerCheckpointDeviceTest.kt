package com.changyow.mediadler.data.ytdlp

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.*
import com.changyow.mediadler.core.repo.DownloadOutput
import com.changyow.mediadler.core.repo.Downloader
import com.changyow.mediadler.data.queue.SqliteDownloadQueueStore
import com.changyow.mediadler.download.DownloadQueue
import com.changyow.mediadler.download.DownloadRunner
import com.changyow.mediadler.download.QueuedRequestResolver
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in disposable-emulator QA. Real native callbacks; no service, UI, or history observer. */
@RunWith(AndroidJUnit4::class)
class AdRunnerCheckpointDeviceTest {
    @Test fun productionRunnerRetainsAnalysisAndCleansOwnWork() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires qaRunnerCheckpoint=true", args.getString("qaRunnerCheckpoint") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", ignoreCase = true))) {
            "This harness is restricted to a disposable Android emulator"
        }
        val url = requireNotNull(args.getString("qaRunnerFixtureUrl"))
        val parsed = URI(url)
        require(parsed.scheme == "http" && parsed.host in setOf("10.0.2.2", "127.0.0.1", "localhost") &&
            parsed.userInfo == null && parsed.path.endsWith(".m3u8")) { "Use the local 18-second HLS fixture" }
        val context = instrumentation.targetContext
        val container = context.appContainer
        val originalQueue = container.downloadQueue
        check(originalQueue.tasks.value.isEmpty() && originalQueue.pauseReason() == null) { "Disposable queue must be empty" }
        check(container.downloadRunner.state.value.phase == DownloadRunner.Phase.IDLE) { "Application runner must be idle" }
        val settingsBefore = container.settingsRepository.settings.first()
        val historyBefore = Json.encodeToString(container.historyStore.history.first())
        fun queueSnapshot(): String = SQLiteDatabase.openDatabase(
            context.getDatabasePath("download_queue.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db -> db.rawQuery("SELECT id, ordinal, payload FROM download_queue ORDER BY ordinal", null).use { rows ->
            buildString { while (rows.moveToNext()) append("${rows.getString(0)}|${rows.getLong(1)}|${rows.getString(2)}\n") }
        } }
        val queueBefore = queueSnapshot()
        check(queueBefore.isEmpty()) { "Queue must have no hidden deletion records" }
        val session = "qa_runner_${UUID.randomUUID()}"
        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), session).apply { check(mkdir()) }
        File(evidence, "original-queue-before.txt").writeText(queueBefore)
        // Recovery observes only container.downloadQueue. This durable queue is never registered with it.
        val queueContext = SessionQueueContext(context, "${session}_download_queue.db")
        val store = SqliteDownloadQueueStore(queueContext)
        val trustedWorkRoot = File(context.noBackupFilesDir.canonicalFile, "download-work")
        val queue = DownloadQueue(store, trustedWorkRoot)
        check(queue.workDirectory(session).canonicalFile == originalQueue.workDirectory(session).canonicalFile)
        check(queue.tasks.value.isEmpty() && store.read().isEmpty())
        val progress = File(evidence, "progress.jsonl")
        val report = JSONObject().put("session", session).put("fixture_url", url)
            .put("source", "EIGHT_MAPLE").put("policy", "WEIGHTED")
            .put("queue_isolation", "production DownloadQueue and SqliteDownloadQueueStore in session-only database")
            .put("queue_database", queueContext.databaseFile.absolutePath)
            .put("trusted_work_root", trustedWorkRoot.absolutePath)
            .put("test_seam", "downloader callback wrapper; injected exception is test-only")
            .put("synthetic_progress", false).put("actual_home_tap_tested", false)
        val ids = mutableListOf<String>()
        val publications = ConcurrentHashMap<String, Uri>()
        val mode = AtomicReference("pause")
        val action = AtomicBoolean(false)
        val injected = AtomicBoolean(false)
        val attempts = AtomicInteger(0)
        val nativeCalls = AtomicInteger(0)
        val firstRestore = AtomicReference<Int?>(null)
        val runnerFailure = AtomicReference<Throwable?>(null)
        val latest = AtomicReference<String?>(null)
        val completedPattern = Regex("候選已處理\\s+(\\d+)/")
        fun count(detail: String) = completedPattern.find(detail)?.groupValues?.get(1)?.toInt()
        fun log(value: JSONObject) = synchronized(progress) { progress.appendText(value.toString() + "\n") }
        fun export(id: String, label: String) {
            val work = queue.workDirectory(id)
            if (work.exists()) check(work.copyRecursively(File(evidence, label), overwrite = true))
        }
        val runnerJob = SupervisorJob()
        val scope = CoroutineScope(runnerJob + Dispatchers.IO)
        lateinit var runner: DownloadRunner
        val wrapped = object : Downloader {
            override suspend fun recover(processId: String, onProgress: (DownloadProgress) -> Unit): Result<DownloadOutput?> =
                container.downloader.recover(processId, onProgress)

            override suspend fun download(request: DownloadRequest, processId: String,
                onProgress: (DownloadProgress) -> Unit): Result<DownloadOutput> {
                check(processId in ids) { "Runner must never claim another task" }
                assertEquals(AdRemovalPolicy.WEIGHTED, request.adRemovalPolicy)
                val attempt = attempts.incrementAndGet()
                nativeCalls.incrementAndGet()
                try {
                    val result = container.downloader.download(request, processId) { event ->
                        onProgress(event) // Production runner publishes actual progress to its queue.
                        val detail = event.detail.orEmpty()
                        latest.set(detail)
                        val done = count(detail)
                        if (detail.startsWith("恢復既有分析") && done != null) firstRestore.compareAndSet(null, done)
                        log(JSONObject().put("time_ms", System.currentTimeMillis()).put("id", processId)
                            .put("attempt", attempt).put("mode", mode.get()).put("phase", event.phase.name)
                            .put("fraction", event.fraction).put("detail", event.detail).put("can_resume", event.canResume)
                            .put("queue_status", queue.get(processId)?.status?.name)
                            .put("queue_detail", queue.get(processId)?.progressDetail))
                        val probing = detail.startsWith("分析候選影音") || detail.startsWith("辨識候選文字")
                        when {
                            mode.get() == "pause" && probing && (done ?: 0) >= 2 && action.compareAndSet(false, true) ->
                                runBlocking { check(runner.pauseTask(processId)) }
                            mode.get() == "failure" && probing && (done ?: 0) >= 4 && injected.compareAndSet(false, true) -> {
                                log(JSONObject().put("test_only_injected_failure", true).put("detail", detail))
                                throw InjectedAnalysisFailure()
                            }
                            mode.get() in setOf("cancel", "delete") && probing && (done ?: 0) >= 1 &&
                                action.compareAndSet(false, true) -> {
                                if (mode.get() == "cancel") queue.cancel(processId) else queue.remove(processId)
                            }
                        }
                    }
                    result.getOrNull()?.let { output ->
                        publications[processId] = Uri.parse(output.uri)
                        // queue.complete deletes private work immediately after this return.
                        export(processId, "final-work")
                    }
                    return result
                } finally {
                    nativeCalls.decrementAndGet()
                    log(JSONObject().put("native_downloader_returned_after_cleanup", true)
                        .put("id", processId).put("attempt", attempt).put("time_ms", System.currentTimeMillis()))
                }
            }
        }
        runner = DownloadRunner(queue, QueuedRequestResolver(container.mediaExtractor), wrapped, scope,
            listener = object : DownloadRunner.Listener {
                override suspend fun onFailed(task: DownloadTask, error: Throwable) {
                    runnerFailure.set(error)
                    log(JSONObject().put("runner_failure", error.stackTraceToString()).put("id", task.id)
                        .put("test_seam", "injected callback failure; native cleanup precedes this notification"))
                }
            },
            networkAvailable = MutableStateFlow(true))
        suspend fun settled(predicate: () -> Boolean) = withTimeout(600_000) {
            while (!predicate() || nativeCalls.get() != 0 || runner.state.value.phase in
                setOf(DownloadRunner.Phase.RUNNING, DownloadRunner.Phase.STOPPING)) delay(20)
        }
        fun submit(): String {
            val format = MediaFormat("qa-local-hls", "mp4", "QA HLS", null, true, true, false, null, MediaResource(url))
            val item = MediaItem(url, null, session, session, null, null, false, listOf(format),
                SourceContext(url, url, SourceKind.EIGHT_MAPLE), DownloadTarget.DIRECT)
            return queue.submitBatch(listOf(QueuedSpec.Resolved(DownloadRequest(item, FormatSelection.BestVideo,
                AdRemovalPolicy.WEIGHTED))), adRemovalPolicy = AdRemovalPolicy.WEIGHTED).single().also { id ->
                ids += id
                val persistedRecord = store.read().single { it.task.id == id }
                assertEquals(AdRemovalPolicy.WEIGHTED, persistedRecord.adRemovalPolicy)
                val persisted = JSONObject(Json.encodeToString(persistedRecord))
                File(evidence, "$id-queue-at-submit.json").writeText(persisted.toString(2))
            }
        }
        var failure: Throwable? = null
        try {
            val id = submit()
            report.put("primary_task_id", id)
            runner.wake(session, 1)
            settled { queue.get(id)?.status == DownloadStatus.PAUSED }
            check(action.get())
            val paused = prefix(queue.workDirectory(id))
            check(paused.size >= 2)
            noFinalPlan(queue.workDirectory(id))
            val input = queue.workDirectory(id).walkTopDown().single { it.isFile && it.name == "ad-input.json" }
            val manifest = JSONObject(input.readText()).getString("manifest")
            val duration = Regex("(?m)^#EXTINF:([0-9.]+),").findAll(manifest).sumOf { it.groupValues[1].toDouble() }
            assertEquals("Fixture must be exactly 18 seconds", 18.0, duration, 0.001)
            export(id, "paused-work")
            report.put("pause", prefixReport(paused)).put("pause_actual_detail", latest.get())
            mode.set("failure")
            check(runner.resumeTask(id))
            runner.wake(session, 2, userInitiated = true)
            settled { queue.get(id)?.status == DownloadStatus.FAILED ||
                queue.get(id)?.queuePause?.kind == QueuePauseKind.NETWORK }
            check(injected.get()) { "Test-only analysis failure was not injected" }
            val failed = prefix(queue.workDirectory(id))
            assertPrefix(paused, failed)
            paused.forEach { (path, record) -> assertEquals(record.modified, requireNotNull(failed[path]).modified) }
            check(failed.size >= 4)
            noFinalPlan(queue.workDirectory(id))
            export(id, "failed-work")
            report.put("injected_failure", prefixReport(failed)).put("failure_runner_status", queue.get(id)?.status?.name)
                .put("first_pause_resume_restored_count", firstRestore.get())
            assertEquals(paused.size, requireNotNull(firstRestore.get()))
            if (queue.get(id)?.status == DownloadStatus.FAILED) {
                check(generateSequence(runnerFailure.get()) { it.cause }.any { it is InjectedAnalysisFailure }) {
                    "Runner failure must be caused by the labelled test injection"
                }
            }
            mode.set("finish")
            firstRestore.set(null)
            if (queue.get(id)?.status == DownloadStatus.FAILED) check(queue.retry(id))
            else queue.resumeNetwork()
            runner.wake(session, 3, userInitiated = true)
            settled { queue.get(id)?.status == DownloadStatus.COMPLETED }
            assertEquals(failed.size, requireNotNull(firstRestore.get()))
            val finalWork = File(evidence, "final-work")
            assertPrefix(failed, prefix(finalWork))
            val plan = JSONObject(finalWork.walkTopDown().single { it.isFile && it.name == "ad-plan.json" }.readText())
            val cost = plan.getJSONObject("cost")
            val checkpoint = cost.getJSONObject("checkpoint")
            assertEquals(failed.size, checkpoint.getInt("reused_candidates"))
            val remaining = plan.getJSONObject("coverage").getInt("candidates_scanned") - failed.size
            assertEquals(remaining, checkpoint.getInt("new_candidates"))
            assertEquals(remaining, cost.getJSONObject("timing").getJSONObject("categories")
                .getJSONObject("pure_scoring").getInt("calls"))
            val saved = JSONObject(String(failed.values.last().bytes, Charsets.UTF_8))
                .getJSONObject("payload").getDouble("cumulative_elapsed_seconds")
            assertEquals(saved, checkpoint.getDouble("saved_elapsed_seconds"), 0.000001)
            val publication = requireNotNull(publications[id])
            val publishedCopy = File(evidence, "published.mp4")
            requireNotNull(context.contentResolver.openInputStream(publication)).use { inputStream ->
                publishedCopy.outputStream().use { inputStream.copyTo(it) }
            }
            val publishedHash = sha(publishedCopy.readBytes())
            assertEquals("84c6b2b831639142d57b06b32cf4d67b8d9e7f0bad8d95c108bd69070c8b70bb", publishedHash)
            check(!queue.workDirectory(id).exists()) { "Production completion must remove private scratch" }
            report.put("final_publication_sha256", publishedHash).put("final_cost", cost)
                .put("completed_private_scratch_removed", true).put("final_retry_restored_count", firstRestore.get())
            queue.remove(id)
            for (operation in listOf("cancel", "delete")) {
                mode.set(operation)
                action.set(false)
                val ownId = submit()
                runner.wake(session, if (operation == "cancel") 4 else 6, userInitiated = true)
                settled { if (operation == "cancel") queue.get(ownId)?.status == DownloadStatus.CANCELED
                    else queue.get(ownId) == null }
                check(action.get())
                if (operation == "cancel") {
                    check(prefix(queue.workDirectory(ownId)).isNotEmpty())
                    noFinalPlan(queue.workDirectory(ownId))
                    export(ownId, "canceled-work")
                } else check(!queue.workDirectory(ownId).exists())
                val calls = attempts.get()
                runner.wake(session, if (operation == "cancel") 5 else 7, userInitiated = true)
                delay(500)
                assertEquals("$operation must not auto-revive on wake", calls, attempts.get())
                check(publications[ownId] == null)
                queue.remove(ownId)
                check(queue.get(ownId) == null && !queue.workDirectory(ownId).exists())
                report.put(operation, JSONObject().put("task_id", ownId).put("no_auto_revival", true)
                    .put("private_scratch_removed_after_delete", true).put("native_cleanup_awaited", true))
            }
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("error", error.stackTraceToString())
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeout(120_000) { runner.stopAndAwait(session); runnerJob.cancelAndJoin() }
                    for (id in ids) {
                        if (queue.workDirectory(id).exists()) export(id, "cleanup-work-$id")
                        publications[id]?.let { uri ->
                            check(if (DocumentsContract.isDocumentUri(context, uri))
                                DocumentsContract.deleteDocument(context.contentResolver, uri)
                            else context.contentResolver.delete(uri, null, null) > 0)
                        }
                        queue.remove(id)
                        check(queue.get(id) == null && !queue.workDirectory(id).exists())
                    }
                    check(queue.tasks.value.isEmpty() && store.read().isEmpty())
                    // The store has no public close API. Close its exact helper before deleting this
                    // test's DB; modern SQLiteOpenHelper may open directly via getDatabasePath.
                    val helper = SqliteDownloadQueueStore::class.java.getDeclaredField("helper")
                        .apply { isAccessible = true }.get(store) as SQLiteOpenHelper
                    helper.close()
                    queueContext.closeAndDeleteOwnDatabase()
                    report.put("session_queue_database_removed", true)
                    assertEquals(historyBefore, Json.encodeToString(container.historyStore.history.first()))
                    assertEquals(settingsBefore, container.settingsRepository.settings.first())
                    report.put("cleanup_and_preservation", "passed")
                } catch (cleanup: Throwable) {
                    failure?.addSuppressed(cleanup) ?: run { failure = cleanup }
                    report.put("cleanup_and_preservation", "failed").put("cleanup_error", cleanup.stackTraceToString())
                }
                try {
                    val queueAfter = queueSnapshot()
                    File(evidence, "original-queue-after.txt").writeText(queueAfter)
                    assertEquals(queueBefore, queueAfter)
                    report.put("original_queue_unchanged", true)
                } catch (preservation: Throwable) {
                    failure?.addSuppressed(preservation) ?: run { failure = preservation }
                    report.put("cleanup_and_preservation", "failed")
                        .put("original_queue_preservation_error", preservation.stackTraceToString())
                }
                File(evidence, "result.json").writeText(report.toString(2))
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "QA_RUNNER_CHECKPOINT_EVIDENCE=${evidence.absolutePath}\n")
                })
            }
        }
        failure?.let { throw it }
    }

    private class InjectedAnalysisFailure : IllegalStateException("QA_TEST_ONLY_INJECTED_ANALYSIS_FAILURE")
    private data class Record(val bytes: ByteArray, val modified: Long)

    /** Keep SQLiteOpenHelper's applicationContext and every database entry point session-local. */
    private class SessionQueueContext(base: Context, private val databaseName: String) : ContextWrapper(base) {
        val databaseFile: File = base.getDatabasePath(databaseName).canonicalFile
        private val handles = mutableSetOf<SQLiteDatabase>()
        private val ownedFiles: List<File> = listOf("", "-journal", "-wal", "-shm").map {
            File(requireNotNull(databaseFile.parentFile), databaseName + it)
        }

        init {
            require(databaseName.matches(Regex("qa_runner_[0-9a-f-]{36}_download_queue\\.db")))
            check(databaseFile.parentFile == base.getDatabasePath("download_queue.db").canonicalFile.parentFile)
            check(databaseFile.name == databaseName)
            check(ownedFiles.none { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }) {
                "Session database and sidecars must be new"
            }
        }

        override fun getApplicationContext(): Context = this
        private fun mapped(name: String): String {
            require(name == "download_queue.db" || name == databaseName)
            return databaseName
        }
        override fun getDatabasePath(name: String): File {
            mapped(name)
            return databaseFile
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(mapped(name), mode, factory).also { synchronized(handles) { handles += it } }

        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(mapped(name), mode, factory, errorHandler)
                .also { synchronized(handles) { handles += it } }

        // Called only after runner cancellation has joined and the durable test queue is empty.
        fun closeAndDeleteOwnDatabase() {
            synchronized(handles) { handles.filter { it.isOpen }.forEach { it.close() }; handles.clear() }
            ownedFiles.forEach { file ->
                check(file.parentFile?.canonicalFile == databaseFile.parentFile &&
                    !java.nio.file.Files.isSymbolicLink(file.toPath()))
                check(!file.exists() || file.isFile && file.delete()) { "Cannot delete own database file: ${file.name}" }
            }
        }
    }

    private fun noFinalPlan(work: File) = check(work.walkTopDown().none {
        it.isFile && it.name in setOf("ad-plan.json", "publication.json")
    }) { "Interruption must precede frozen plan and publication" }

    private fun prefix(work: File): Map<String, Record> {
        val storage = work.walkTopDown().single { it.isDirectory && it.name == "analysis-checkpoint" && it.parentFile?.name == ".resume" }
        val active = File(storage, "active.json").readBytes()
        val directory = requireNotNull(storage.listFiles()).single { it.isDirectory &&
            it.name.matches(Regex("[0-9a-f]{64}")) && File(it, "header.json").isFile &&
            File(it, "header.json").readBytes().contentEquals(active) }
        return requireNotNull(directory.listFiles()).filter { it.name.matches(Regex("\\d{6}\\.json")) }
            .sortedBy { it.name }.mapIndexed { ordinal, file ->
                check(file.isFile && file.name == "%06d.json".format(java.util.Locale.US, ordinal))
                val raw = file.readBytes()
                val record = JSONObject(String(raw, Charsets.UTF_8))
                check(record.getInt("ordinal") == ordinal)
                listOf("features", "weighted", "row").forEach { record.getJSONObject("payload").getJSONObject(it) }
                file.relativeTo(work).path to Record(raw, file.lastModified())
            }.toMap()
    }

    private fun assertPrefix(before: Map<String, Record>, after: Map<String, Record>) {
        before.forEach { (path, record) ->
            val retained = requireNotNull(after[path]) { "Missing retained candidate $path" }
            assertTrue("Raw completed candidate changed: $path", record.bytes.contentEquals(retained.bytes))
            // copyRecursively does not preserve mtimes; raw payload holds authoritative saved elapsed time.
        }
    }

    private fun prefixReport(prefix: Map<String, Record>) = JSONObject().put("completed_candidates", prefix.size)
        .put("records", JSONObject().apply { prefix.forEach { (path, record) ->
            put(path, JSONObject().put("raw_sha256", sha(record.bytes)).put("file_modified_ms", record.modified)
                .put("saved_elapsed_seconds", JSONObject(String(record.bytes, Charsets.UTF_8))
                    .getJSONObject("payload").getDouble("cumulative_elapsed_seconds")))
        } })

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
