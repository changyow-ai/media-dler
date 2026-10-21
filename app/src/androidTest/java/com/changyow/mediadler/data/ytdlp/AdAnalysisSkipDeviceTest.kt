package com.changyow.mediadler.data.ytdlp

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.MainActivity
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.*
import com.changyow.mediadler.download.DownloadRunner
import com.changyow.mediadler.download.DownloadService
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in QA app only. A human/test operator clicks the real Home action; this test never does. */
@RunWith(AndroidJUnit4::class)
class AdAnalysisSkipDeviceTest {
    @Test fun realHomeSkipThroughApplicationRunner() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires qaAdAnalysisSkip=true", args.getString("qaAdAnalysisSkip") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "com.changyow.mediadler.qa") { "Only the isolated QA target is allowed" }
        val session = UUID.fromString(requireNotNull(args.getString("qaSkipSession"))).toString()
        val url = requireNotNull(args.getString("qaSkipFixtureUrl"))
        val parsed = URI(url)
        require(parsed.scheme in setOf("http", "https") && parsed.host != null && parsed.userInfo == null)
        val source = args.getString("qaSkipSourceKind")?.let(SourceKind::valueOf) ?: SourceKind.EIGHT_MAPLE
        val expectSkipped = requireNotNull(args.getString("qaSkipExpectSkipped")).let {
            require(it in setOf("true", "false")); it == "true"
        }
        val timeoutSeconds = args.getString("qaSkipTimeoutSeconds")?.toLong() ?: 360L
        require(timeoutSeconds in 1..600)
        val container = context.appContainer
        val queue = container.downloadQueue
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun records(): List<QueuedDownload> = SQLiteDatabase.openDatabase(
            context.getDatabasePath("download_queue.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db -> db.rawQuery("SELECT payload FROM download_queue ORDER BY ordinal", null).use { rows ->
            buildList { while (rows.moveToNext()) add(json.decodeFromString<QueuedDownload>(rows.getString(0))) }
        } }
        val beforeQueue = records()
        val beforeHistory = container.historyStore.history.first()
        val beforeSettings = container.settingsRepository.settings.first()
        check(beforeQueue.isEmpty() && queue.tasks.value.isEmpty() && beforeHistory.isEmpty()) {
            "QA queue and history must already be empty; existing work is never altered"
        }
        check(queue.pauseReason() == null) { "Existing queue pause must be preserved" }
        check(container.downloadRunner.state.value.phase !in setOf(DownloadRunner.Phase.RUNNING, DownloadRunner.Phase.STOPPING)) {
            "Existing runner work must finish first"
        }
        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), "qa_ad_skip_$session")
        check(evidence.mkdir()) { "Use a fresh qaSkipSession UUID; exports are never overwritten" }
        val progressFile = File(evidence, "progress.jsonl")
        val report = JSONObject().put("session", session).put("url", url).put("source", source.name)
            .put("expect_skipped", expectSkipped).put("policy", AdRemovalPolicy.WEIGHTED.name)
            .put("synthetic_progress", false).put("programmatic_skip", false)
            .put("external_exports_retained_for_host_pull", true)
            .put("original_rate_limit_bytes_per_second", beforeSettings.downloadRateLimitBytesPerSecond)
            .put("qa_rate_limit_bytes_per_second", 512 * 1024).put("rate_limit_scope", context.packageName)
        var id: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var publication: Uri? = null
        var failure: Throwable? = null
        fun failed(error: Throwable) { failure?.addSuppressed(error) ?: run { failure = error } }
        fun marker(value: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
        }
        // Media is exported only after publication. During work retain small metadata before scratch deletion.
        fun snapshotMetadata() {
            val ownId = id ?: return
            val work = queue.workDirectory(ownId)
            if (!work.exists()) return
            val manifest = JSONArray()
            val exportRoot = File(evidence, "work-metadata")
            work.walkTopDown().maxDepth(6).filter { it.isFile && it.extension in setOf("json", "jsonl", "txt") }
                .take(64).forEach { file ->
                    val relative = file.relativeTo(work).path
                    val size = file.length()
                    val entry = JSONObject().put("path", relative).put("bytes", size)
                    val target = File(exportRoot, relative)
                    val exported = if (exportRoot.exists()) exportRoot.walkTopDown().filter { it.isFile }.toList() else emptyList()
                    if (size <= 512 * 1024 && (target.exists() || exported.size < 64) &&
                        exported.sumOf { it.length() } - target.length() + size <= 4 * 1024 * 1024) {
                        // A native rename/delete may race this optional evidence snapshot.
                        val copied = runCatching {
                            val buffer = ByteArray(512 * 1024 + 1)
                            var length = 0
                            file.inputStream().use { input ->
                                while (length < buffer.size) {
                                    val read = input.read(buffer, length, buffer.size - length)
                                    if (read < 0) break
                                    length += read
                                }
                            }
                            check(length <= 512 * 1024 &&
                                exported.sumOf { it.length() } - target.length() + length <= 4 * 1024 * 1024)
                            target.parentFile?.mkdirs()
                            target.writeBytes(buffer.copyOf(length))
                        }.isSuccess
                        entry.put("copied", copied)
                    } else entry.put("copied", false)
                    manifest.put(entry)
                }
            File(evidence, "work-metadata-manifest.json").writeText(manifest.toString(2))
        }
        var sawAnalysis = false
        var sawTransferAfterAnalysis = false
        var sawTransferAfterSkip = false
        var analysisAttempt: Int? = null
        var progressRows = 0
        var lastSnapshot = 0L
        var monitor: kotlinx.coroutines.Job? = null
        try {
            container.settingsRepository.update { it.copy(downloadRateLimitBytesPerSecond = 512L * 1024) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            marker("QA_AD_SKIP_FOREGROUND_WINDOW_CONFIGURED=true")
            val title = "廣告分析略過 QA $session"
            val format = MediaFormat("qa-hls", "mp4", "QA HLS", null, true, true, false, null, MediaResource(url))
            val item = MediaItem(url, null, session, title, null, null, false, listOf(format),
                SourceContext(url, url, source), DownloadTarget.DIRECT)
            val request = DownloadRequest(item, FormatSelection.BestVideo, AdRemovalPolicy.WEIGHTED)
            val ownId = queue.submitBatch(listOf(QueuedSpec.Resolved(request)), adRemovalPolicy = AdRemovalPolicy.WEIGHTED).single()
            id = ownId
            report.put("task_id", ownId)
            File(evidence, "task-id.txt").writeText(ownId)
            marker("QA_AD_SKIP_TASK_ID=$ownId")
            marker("QA_AD_SKIP_EVIDENCE=${evidence.absolutePath}")
            monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                queue.tasks.collect { tasks ->
                    val task = tasks.firstOrNull { it.id == ownId } ?: return@collect
                    val durable = records().firstOrNull { it.task.id == ownId }
                    val row = JSONObject().put("time_ms", System.currentTimeMillis()).put("id", ownId)
                        .put("status", task.status.name).put("phase", task.progressPhase?.name)
                        .put("skip_ad_analysis", task.skipAdAnalysis).put("interruption", task.interruption?.name)
                        .put("attempt", durable?.attempt).put("durable_skip_ad_analysis", durable?.task?.skipAdAnalysis)
                        .put("progress", task.progress).put("detail", task.progressDetail?.take(4096))
                    if (++progressRows <= 6000) progressFile.appendText(row.toString() + "\n")
                    if (task.progressPhase == DownloadPhase.AD_ANALYSIS) {
                        check(!sawTransferAfterSkip) { "Analysis restarted after skipped full-content transfer" }
                        if (!sawAnalysis) {
                            sawAnalysis = true
                            analysisAttempt = durable?.attempt
                            File(evidence, "READY.json").writeText(row.toString(2))
                            marker("QA_AD_SKIP_READY=$ownId")
                        }
                    }
                    if (sawAnalysis && task.progressPhase == DownloadPhase.DOWNLOADING) {
                        if (!sawTransferAfterAnalysis) marker("QA_AD_SKIP_TRUE_DOWNLOAD=$ownId")
                        sawTransferAfterAnalysis = true
                        if (task.skipAdAnalysis) sawTransferAfterSkip = true
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastSnapshot >= 500 || task.interruption != null || task.status.isTerminal) {
                        snapshotMetadata()
                        lastSnapshot = now
                    }
                }
            }
            withTimeout(timeoutSeconds * 1000) {
                check(DownloadService.start(context)) { "QA foreground service could not start" }
                val terminal = queue.tasks.first { tasks -> tasks.any { it.id == ownId && it.status.isTerminal } }
                    .first { it.id == ownId }
                publication = terminal.outputUri?.let(Uri::parse)
                report.put("terminal_task", JSONObject(json.encodeToString(terminal)))
                assertEquals(DownloadStatus.COMPLETED, terminal.status)
                assertNull(terminal.errorMessage)
                val output = File(evidence, "publication.mp4")
                requireNotNull(context.contentResolver.openInputStream(requireNotNull(publication))).use { input ->
                    output.outputStream().use { input.copyTo(it) }
                }
                check(output.length() > 0)
                report.put("published_bytes", output.length())
                // History follows preview generation in the real DownloadObserver; wait before cleanup.
                container.historyStore.history.first { tasks -> tasks.any { it.id == ownId } }
                container.downloadRunner.state.first { it.phase !in setOf(DownloadRunner.Phase.RUNNING, DownloadRunner.Phase.STOPPING) }
                val durable = requireNotNull(records().firstOrNull { it.task.id == ownId })
                File(evidence, "terminal-record.json").writeText(json.encodeToString(durable))
                assertTrue("Actual typed analysis was not observed", sawAnalysis)
                assertTrue("Actual transfer after analysis was not observed", sawTransferAfterAnalysis)
                assertEquals(expectSkipped, durable.task.skipAdAnalysis)
                if (expectSkipped) {
                    assertTrue("Skipped full-content transfer was not observed", sawTransferAfterSkip)
                    assertTrue("Skip must start a new native attempt", durable.attempt > requireNotNull(analysisAttempt))
                }
                report.put("status", "completed")
            }
        } catch (error: Throwable) {
            failed(error)
            report.put("status", "failed").put("error", error.toString())
        } finally {
            withContext(NonCancellable) {
                monitor?.cancelAndJoin()
                runCatching { snapshotMetadata() }.exceptionOrNull()?.let(::failed)
                val cleanup = JSONObject()
                suspend fun cleanupStep(name: String, block: suspend () -> Unit) {
                    try { block(); cleanup.put(name, "passed") }
                    catch (error: Throwable) { cleanup.put(name, error.toString()); failed(error) }
                }
                var stopped = id == null
                cleanupStep("native_stop_and_observer") {
                    id?.let { ownId ->
                        queue.get(ownId)?.takeIf { !it.status.isTerminal }?.let { queue.cancel(ownId) }
                        withTimeout(30_000) {
                            queue.tasks.first { tasks -> tasks.none { it.id == ownId && it.status.isActive } }
                            container.downloadRunner.state.first {
                                it.phase !in setOf(DownloadRunner.Phase.RUNNING, DownloadRunner.Phase.STOPPING)
                            }
                        }
                    }
                    stopped = true
                }
                if (stopped) {
                    cleanupStep("publication") {
                        publication = publication ?: id?.let(queue::get)?.outputUri?.let(Uri::parse)
                        publication?.let { uri ->
                            check(if (DocumentsContract.isDocumentUri(context, uri))
                                DocumentsContract.deleteDocument(context.contentResolver, uri)
                            else context.contentResolver.delete(uri, null, null) > 0) { "Owned publication cleanup failed" }
                        }
                    }
                    cleanupStep("owned_history_preview_queue_work") {
                        id?.let { ownId ->
                            val historyTask = container.historyStore.history.first().firstOrNull { it.id == ownId }
                            val preview = queue.get(ownId)?.previewPath ?: historyTask?.previewPath
                            container.previewStore.delete(preview)
                            container.historyStore.remove(ownId)
                            queue.remove(ownId)
                            check(queue.get(ownId) == null && !queue.workDirectory(ownId).exists())
                            check(preview == null || !File(preview).exists())
                            check(container.historyStore.history.first().none { it.id == ownId })
                        }
                    }
                } else cleanup.put("owned_cleanup", "left in place: native exit not confirmed")
                cleanupStep("qa_rate_restored") {
                    container.settingsRepository.update {
                        it.copy(downloadRateLimitBytesPerSecond = beforeSettings.downloadRateLimitBytesPerSecond)
                    }
                }
                cleanupStep("nonowned_state_preserved") {
                    assertEquals(beforeQueue, records().filter { it.task.id != id })
                    assertEquals(beforeHistory, container.historyStore.history.first().filter { it.id != id })
                    assertEquals(beforeSettings, container.settingsRepository.settings.first())
                }
                cleanupStep("activity_close") { scenario?.close() }
                report.put("saw_analysis", sawAnalysis).put("saw_transfer_after_analysis", sawTransferAfterAnalysis)
                    .put("saw_transfer_after_skip", sawTransferAfterSkip).put("progress_rows", progressRows)
                    .put("cleanup", cleanup).put("failure", failure?.stackTraceToString())
                File(evidence, "result.json").writeText(report.toString(2))
                marker("QA_AD_SKIP_EVIDENCE=${evidence.absolutePath}")
            }
        }
        failure?.let { throw it }
    }
}
