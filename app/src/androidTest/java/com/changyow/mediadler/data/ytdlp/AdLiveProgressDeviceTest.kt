package com.changyow.mediadler.data.ytdlp

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.*
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit physical-device QA: actual short local HLS callbacks, never seeded counters. */
@RunWith(AndroidJUnit4::class)
class AdLiveProgressDeviceTest {
    @Test fun realHlsCallbacksReachActualHomeCard() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit physical QA authorization", args.getString("qaLiveAdProgress") == "true")
        val url = requireNotNull(args.getString("qaLiveAdFixtureUrl"))
        val parsed = URI(url)
        require(parsed.scheme in setOf("http", "https") && parsed.host != null && parsed.userInfo == null)
        val policy = args.getString("qaLiveAdPolicy")?.let(AdRemovalPolicy::valueOf) ?: AdRemovalPolicy.WEIGHTED
        val observationSeconds = args.getString("qaLiveAdObservationSeconds")?.toLong() ?: 60L
        require(observationSeconds in 0..120)
        val context = instrumentation.targetContext
        val container = context.appContainer
        val queue = container.downloadQueue
        check(queue.tasks.value.none { it.status.isActive || it.status == DownloadStatus.QUEUED }) {
            "User downloads must be idle; no active or queued tasks may be present"
        }
        check(queue.pauseReason() == null) { "Existing queue pause must be preserved; cannot claim QA work" }
        val session = "qa_live_ad_${UUID.randomUUID()}"
        var id: String? = null
        fun queueSnapshot(): List<String> = SQLiteDatabase.openDatabase(
            context.getDatabasePath("download_queue.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY
        ).use { db -> db.rawQuery("SELECT id, ordinal, payload FROM download_queue ORDER BY ordinal", null).use { rows ->
            buildList { while (rows.moveToNext()) if (rows.getString(0) != id)
                add("${rows.getString(0)}|${rows.getLong(1)}|${rows.getString(2)}") }
        } }
        suspend fun historySnapshot() = Json.encodeToString(container.historyStore.history.first().filter { it.id != id })
        val beforeQueue = queueSnapshot()
        val beforeHistory = historySnapshot()
        val beforeSettings = container.settingsRepository.settings.first()
        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), session).apply { check(mkdir()) }
        val progress = File(evidence, "progress.jsonl")
        val report = JSONObject().put("session", session).put("url", url).put("policy", policy.name)
            .put("synthetic_progress", false).put("manual_ad_hints", false)
        val format = MediaFormat("qa-local-hls", "mp4", "QA HLS", null, true, true, false, null, MediaResource(url))
        val item = MediaItem(url, null, session, "實際短 HLS 分析 QA $session", null, null, false, listOf(format),
            SourceContext(url, url, SourceKind.EIGHT_MAPLE), DownloadTarget.DIRECT)
        val request = DownloadRequest(item, FormatSelection.BestVideo, policy)
        var claim: DownloadClaim? = null
        var publication: Uri? = null
        var failure: Throwable? = null
        var lastStage: String? = null
        var sawCandidateProgress = false
        fun exportWork() {
            id?.let { taskId -> queue.workDirectory(taskId).takeIf { it.exists() }?.let {
                check(it.copyRecursively(File(evidence, "work"), overwrite = true))
            } }
        }
        fun failed(error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            id = queue.submitBatch(listOf(QueuedSpec.Resolved(request)), adRemovalPolicy = policy).single()
            report.put("task_id", id)
            val ownClaim = requireNotNull(queue.claimNext()).also { claim = it }
            check(ownClaim.id == id) { "Must claim only the newly generated QA UUID" }
            val output = withTimeout(600_000) { container.downloader.download(request, requireNotNull(id)) { event ->
                queue.setProgress(ownClaim, event)
                val value = JSONObject().put("time_ms", System.currentTimeMillis()).put("phase", event.phase.name)
                    .put("fraction", event.fraction).put("detail", event.detail).put("can_resume", event.canResume)
                synchronized(progress) { progress.appendText(value.toString() + "\n") }
                if (event.detail?.contains("候選已處理") == true) sawCandidateProgress = true
                val stage = event.detail?.lineSequence()?.firstOrNull() ?: event.phase.name
                if (stage != lastStage) {
                    lastStage = stage
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "QA_LIVE_AD_STAGE=$stage\n") })
                }
            }.getOrThrow() }
            val uri = Uri.parse(output.uri).also { publication = it }
            report.put("publication_uri", output.uri)
            exportWork()
            val copy = File(evidence, "published.mp4")
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                copy.outputStream().use { input.copyTo(it) }
            }
            check(copy.length() > 0)
            report.put("published_bytes", copy.length()).put("actual_candidate_progress_seen", sawCandidateProgress)
                .put("test_observation_hold_after_native_result_seconds", observationSeconds)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "QA_LIVE_AD_OBSERVATION_HOLD=${observationSeconds}s (native result received; no fabricated counters/time)\n")
            })
            delay(observationSeconds * 1000)
            check(queue.complete(ownClaim, output.uri, output.mimeType))
            if (policy != AdRemovalPolicy.OFF) check(sawCandidateProgress) { "No real candidate counter callback observed" }
            report.put("status", "completed")
        } catch (error: Throwable) {
            failed(error)
            report.put("status", "failed").put("error", error.toString())
        } finally {
            withContext(NonCancellable) {
                val cleanupErrors = mutableListOf<Throwable>()
                fun cleanupFailed(error: Throwable) { cleanupErrors += error; failed(error) }
                runCatching { exportWork() }.exceptionOrNull()?.let(::cleanupFailed)
                runCatching {
                    publication?.let { uri -> check(if (DocumentsContract.isDocumentUri(context, uri))
                        DocumentsContract.deleteDocument(context.contentResolver, uri)
                        else context.contentResolver.delete(uri, null, null) > 0) { "Own publication cleanup failed" } }
                }.exceptionOrNull()?.let(::cleanupFailed)
                runCatching {
                    id?.let { ownId ->
                        queue.remove(ownId)
                        claim?.takeIf { it.id == ownId }?.let { queue.acknowledgeStop(it, interrupted = true) }
                        check(queue.get(ownId) == null && !queue.workDirectory(ownId).exists()) { "Own task cleanup incomplete" }
                    }
                    assertEquals(beforeQueue, queueSnapshot())
                    assertEquals(beforeHistory, historySnapshot())
                    assertEquals(beforeSettings, container.settingsRepository.settings.first())
                }.exceptionOrNull()?.let(::cleanupFailed)
                report.put("cleanup_and_preservation", if (cleanupErrors.isEmpty()) "passed" else "failed")
                    .put("failure", failure?.stackTraceToString())
                File(evidence, "result.json").writeText(report.toString(2))
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "QA_LIVE_AD_EVIDENCE=${evidence.absolutePath}\n") })
            }
        }
        failure?.let { throw it }
    }
}
