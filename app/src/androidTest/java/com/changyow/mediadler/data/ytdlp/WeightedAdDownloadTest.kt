package com.changyow.mediadler.data.ytdlp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.AdRemovalPolicy
import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.MediaResource
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import com.changyow.mediadler.core.model.isActive
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in production-pipeline QA; supplies only a page URL (or an explicit media
 * fixture), never ad times, region indexes, features, fingerprints or decisions.
 * Uses existing destination settings. It does not enqueue or write user history.
 * Exported evidence is retained for the tester to pull and remove explicitly.
 */
@RunWith(AndroidJUnit4::class)
class WeightedAdDownloadTest {
    @Test fun cleanupInterruptedOwnWork() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit QA cleanup", args.getString("qaWeightedCleanup") == "true")
        val session = UUID.fromString(requireNotNull(args.getString("qaWeightedSession"))).toString()
        val id = requireNotNull(args.getString("qaWeightedCleanupTaskId"))
        require(id.matches(Regex("qa_weighted_${session.replace("-", "") }_[0-9a-f]{8}")))
        val context = instrumentation.targetContext
        check(context.appContainer.downloadQueue.tasks.value.none { it.status.isActive }) { "User work is active" }
        val queueBefore = Json.encodeToString(context.appContainer.downloadQueue.tasks.value)
        val historyBefore = Json.encodeToString(context.appContainer.historyStore.history.first())
        val base = File(context.noBackupFilesDir, "download-work")
        val work = File(base, id)
        check(work.parentFile?.canonicalFile == base.canonicalFile &&
            !java.nio.file.Files.isSymbolicLink(work.toPath()))
        check(!File(work, "publication.json").exists()) { "Published QA work needs its own URI cleanup" }
        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), "qa_weighted_interrupted_$session")
        check(evidence.mkdir()) { "Interrupted evidence session must be new" }
        copyEvidence(work, evidence)
        check(!work.exists() || work.deleteRecursively()) { "Could not remove interrupted QA work" }
        assertEquals(queueBefore, Json.encodeToString(context.appContainer.downloadQueue.tasks.value))
        assertEquals(historyBefore, Json.encodeToString(context.appContainer.historyStore.history.first()))
        File(evidence, "interrupted-work.json").writeText(JSONObject().put("task_id", id)
            .put("own_private_work_removed", true).put("queue_history_unchanged", true).toString())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "QA_OWN_WORK_REMOVED=$id\nQA_INTERRUPTED_EXPORT=${evidence.absolutePath}\n")
        })
    }

    @Test fun readOnlyUserState() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit qaWeightedReadOnly=true", args.getString("qaWeightedReadOnly") == "true")
        val session = UUID.fromString(requireNotNull(args.getString("qaWeightedSession"))).toString()
        val stage = requireNotNull(args.getString("qaWeightedStateStage"))
        require(stage in setOf("before", "after"))
        val context = instrumentation.targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "qa_weighted_state_$session")
            .apply { check(isDirectory || mkdir()) }
        val file = File(directory, "$stage.json")
        check(!file.exists())
        val value = JSONObject().put("read_only", true)
            .put("queue", org.json.JSONArray(Json.encodeToString(context.appContainer.downloadQueue.tasks.value)))
            .put("history", org.json.JSONArray(Json.encodeToString(context.appContainer.historyStore.history.first())))
        file.writeText(value.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "QA_WEIGHTED_STATE=${file.absolutePath}\n") })
    }

    @Test fun autonomousDownload() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit qaWeightedAds=true", args.getString("qaWeightedAds") == "true")
        val page = args.getString("qaWeightedPageUrl")
        val fixture = args.getString("qaWeightedFixtureUrl")
        require((page != null) xor (fixture != null)) { "Provide exactly one page or fixture URL" }
        val session = UUID.fromString(requireNotNull(args.getString("qaWeightedSession"))).toString()
        val context = instrumentation.targetContext
        val recoveryId = args.getString("qaWeightedRecoveryTaskId")
        val recoveryAnalysis = args.getString("qaWeightedRecoveryAnalysis") == "true"
        val analysisCancelAfter = args.getString("qaWeightedAnalysisCancelAfter")?.toInt()?.also { require(it > 0) }
        val inspectOnly = args.getString("qaWeightedRecoveryInspectOnly") == "true"
        val evidenceSession = args.getString("qaWeightedEvidenceSession")?.let { UUID.fromString(it).toString() }
            ?: session
        require(!inspectOnly || recoveryId != null)
        require(!recoveryAnalysis || recoveryId != null)
        require(analysisCancelAfter == null || (recoveryId == null && args.getString("qaWeightedCheckpointCancel") != "true"))
        require(recoveryId != null || evidenceSession == session)
        recoveryId?.let {
            require(it.matches(Regex("qa_weighted_${session.replace("-", "") }_[0-9a-f]{8}")))
            require(evidenceSession != session) { "Recovery evidence must not overwrite the original session" }
            require(args.getString("qaWeightedCheckpointCancel") != "true")
            check(context.appContainer.downloadQueue.tasks.value.none { task -> task.status.isActive }) {
                "User work is active"
            }
        }
        val id = recoveryId ?: "qa_weighted_${session.replace("-", "")}_${UUID.randomUUID().toString().take(8)}"
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "QA_WEIGHTED_TASK_ID=$id\n") })
        val base = File(context.noBackupFilesDir, "download-work")
        val work = File(base, id)
        check(work.parentFile?.canonicalFile == base.canonicalFile &&
            !java.nio.file.Files.isSymbolicLink(work.toPath()))
        if (recoveryId != null) check(work.isDirectory) { "Interrupted QA work does not exist" }
        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), "qa_weighted_$evidenceSession")
        check(evidence.mkdir()) { "Evidence session must be new" }
        val progress = File(evidence, "progress.jsonl")
        val partialAudit = File(evidence, "candidate-audit.jsonl")
        var latestAudit: File? = null
        var copiedCandidate = 0
        val report = JSONObject().put("schema", 1).put("task_id", id)
            .put("input_mode", if (page != null) "production_page_extractor" else "explicit_media_fixture")
            .put("input_url", page ?: fixture).put("manual_ad_hints", false)
            .put("analysis_recovery", recoveryAnalysis)
            .put("recovery_mode", if (recoveryId == null) "none" else if (inspectOnly) "inspect_only" else "resume")
        var publication: Uri? = null
        var analysisBefore = emptyMap<String, ByteArray>()
        val restoringAnalysis = AtomicBoolean(false)
        val firstRestoredCount = AtomicReference<Int?>(null)
        val latestProgress = AtomicReference<String?>(null)
        val concreteCount = Regex("候選已處理\\s+(\\d+)/")
        var completed = false
        val started = System.currentTimeMillis()
        try {
            val frozen = if (recoveryId != null) {
                copyEvidence(work, File(evidence, "before").apply { check(mkdir()) })
                val plans = work.walkTopDown().filter { it.isFile && it.name == "ad-plan.json" }.toList()
                if (recoveryAnalysis) {
                    check(plans.isEmpty()) { "Analysis-only recovery must precede the final cutting plan" }
                    check(work.walkTopDown().none { it.isFile && it.name == "publication.json" })
                    analysisBefore = analysisPrefix(work)
                    check(analysisBefore.isNotEmpty()) { "Analysis recovery requires a committed candidate prefix" }
                    report.put("recovery_before", JSONObject().put("completed_candidates", analysisBefore.size)
                        .put("prefix_sha256", prefixHashes(work, analysisBefore)))
                    if (inspectOnly) {
                        report.put("test_status", "inspected_not_resumed")
                        return@runBlocking
                    }
                    restoringAnalysis.set(true)
                    null
                } else {
                    check(plans.size == 1) { "Recovery requires one existing frozen ad decision" }
                    val plan = plans.single()
                    check(JSONObject(plan.readText()).getString("policy") == WeightedHlsAds.POLICY)
                    val checkpoint = File(plan.parentFile, "checkpoint.json")
                    report.put("recovery_before", JSONObject().put("frozen_plan_sha256", sha256(plan))
                        .put("checkpoint_index", if (checkpoint.isFile) JSONObject(checkpoint.readText()).getInt("index") else -1))
                    if (inspectOnly) {
                        copyEvidence(work, evidence)
                        report.put("test_status", "inspected_not_resumed")
                        return@runBlocking
                    }
                    check(work.walkTopDown().none { it.isFile && it.name == "publication.json" }) {
                        "Existing publication needs journal recovery before download"
                    }
                    plan to plan.readBytes()
                }
            } else null
            val item = if (page != null) {
                withTimeout(120_000) { context.appContainer.mediaExtractor.extract(page).getOrThrow() }.single()
            } else directFixture(requireNotNull(fixture), id)
            assertTrue("Must use a supported special-source HLS pipeline", item.source.kind != SourceKind.GENERAL)
            report.put("resolved_media_url", item.sourceUrl).put("source_kind", item.source.kind.name)
            val qaItem = item.copy(id = id, title = id, originalTitle = id,
                seriesTitle = id, seasonNumber = null, episodeNumber = null)
            val seconds = args.getString("qaWeightedTimeoutSeconds")?.toLong() ?: 7200L
            require(seconds in 60..14_400)
            val adPolicy = args.getString("qaAdPolicy")?.let { AdRemovalPolicy.valueOf(it) }
                ?: AdRemovalPolicy.WEIGHTED
            report.put("ad_policy", adPolicy.name)
            require((analysisCancelAfter == null && !recoveryAnalysis) || adPolicy == AdRemovalPolicy.WEIGHTED)
            val request = DownloadRequest(qaItem, FormatSelection.BestVideo, adPolicy)
            suspend fun attempt() = context.appContainer.downloader.download(request, id) { event ->
                    if (recoveryId != null && !recoveryAnalysis && listOf("分析廣告候選", "分析候選影音", "辨識候選文字")
                            .any { event.detail?.startsWith(it) == true }) {
                        throw kotlinx.coroutines.CancellationException("QA_RECOVERY_UNEXPECTED_REANALYSIS")
                    }
                    if (File(evidence, "abort.request").isFile) {
                        throw kotlinx.coroutines.CancellationException("QA_OPERATOR_ABORT")
                    }
                    val value = JSONObject().put("time_ms", System.currentTimeMillis())
                        .put("phase", event.phase.name).put("detail", event.detail).put("can_resume", event.canResume)
                    latestProgress.set(event.detail)
                    if (restoringAnalysis.get()) concreteCount.find(event.detail.orEmpty())?.groupValues?.get(1)
                        ?.toInt()?.let { firstRestoredCount.compareAndSet(null, it) }
                    synchronized(progress) {
                        progress.appendText(value.toString() + "\n")
                        // Read only this QA job. The final full audit remains authoritative.
                        val latest = latestAudit ?: work.walkTopDown().maxDepth(3)
                            .firstOrNull { it.isFile && it.name == "weighted-latest.json" }
                            ?.also { latestAudit = it }
                        if (latest != null && latest.isFile && latest.length() in 1..1024L * 1024) {
                            val raw = latest.readText()
                            val index = JSONObject(raw).getInt("completed_index")
                            if (index > copiedCandidate) {
                                partialAudit.appendText(raw + "\n")
                                copiedCandidate = index
                            }
                        }
                    }
                }.getOrThrow().also { publication = Uri.parse(it.uri) }
            val output = withTimeout(seconds * 1000) {
                if (analysisCancelAfter != null) coroutineScope {
                    val firstAttempt = async { attempt() }
                    var cancellationStage: String
                    while (true) {
                        check(!firstAttempt.isCompleted) { "Analysis cancellation window was missed" }
                        val detail = latestProgress.get().orEmpty()
                        val done = concreteCount.find(detail)?.groupValues?.get(1)?.toInt() ?: -1
                        if (done >= analysisCancelAfter && listOf("分析候選影音", "辨識候選文字").any { detail.startsWith(it) }) {
                            delay(50)
                            val current = latestProgress.get().orEmpty()
                            if (!firstAttempt.isCompleted && listOf("分析候選影音", "辨識候選文字").any { current.startsWith(it) }) {
                                cancellationStage = current
                                break
                            }
                        }
                        delay(10)
                    }
                    firstAttempt.cancelAndJoin()
                    check(firstAttempt.isCancelled && publication == null)
                    check(work.walkTopDown().none { it.isFile && it.name in setOf("ad-plan.json", "publication.json") })
                    analysisBefore = analysisPrefix(work)
                    check(analysisBefore.size >= analysisCancelAfter)
                    report.put("analysis_cancel", JSONObject().put("requested_after", analysisCancelAfter)
                        .put("completed_candidates", analysisBefore.size).put("actual_progress_at_cancel", cancellationStage)
                        .put("acknowledged", true).put("prefix_sha256", prefixHashes(work, analysisBefore)))
                    copyEvidence(work, File(evidence, "analysis-before-resume").apply { check(mkdir()) })
                    restoringAnalysis.set(true)
                    attempt()
                } else if (args.getString("qaWeightedCheckpointCancel") == "true") coroutineScope {
                    val firstAttempt = async { attempt() }
                    var checkpoint: File? = null
                    while (checkpoint == null) {
                        check(!firstAttempt.isCompleted) { "QA checkpoint cancellation window was missed" }
                        checkpoint = work.walkTopDown().maxDepth(3)
                            .firstOrNull { it.isFile && it.name == "checkpoint.json" &&
                                JSONObject(it.readText()).getInt("index") >= 1 }
                        if (checkpoint == null) delay(10)
                    }
                    val plan = File(checkpoint.parentFile, "ad-plan.json")
                    val frozen = plan.readBytes()
                    val committed = JSONObject(checkpoint.readText()).getInt("index")
                    firstAttempt.cancelAndJoin()
                    assertTrue("Canceled native attempt must be acknowledged", firstAttempt.isCancelled)
                    assertNull("Cancellation must precede publication", publication)
                    assertFalse(File(work, "publication.json").exists())
                    assertTrue("Canceled attempt cannot change a frozen decision", frozen.contentEquals(plan.readBytes()))
                    report.put("checkpoint_cancel", JSONObject().put("committed_index", committed)
                        .put("acknowledged", true).put("frozen_plan_sha256", sha256(plan)))
                    attempt().also {
                        assertTrue("Resume must use the same frozen skip set", frozen.contentEquals(plan.readBytes()))
                        report.getJSONObject("checkpoint_cancel").put("resume_same_frozen_plan", true)
                    }
                } else attempt()
            }
            publication = Uri.parse(output.uri)
            report.put("publication_uri", output.uri)
            val plans = work.walkTopDown().filter { it.isFile && it.name == "ad-plan.json" }.toList()
            val expectsPlan = adPolicy == AdRemovalPolicy.WEIGHTED ||
                adPolicy == AdRemovalPolicy.CONFIRMED_ONLY && item.source.kind == SourceKind.EIGHT_MAPLE
            assertEquals("Frozen decisions must follow the task policy", if (expectsPlan) 1 else 0, plans.size)
            frozen?.let { (original, bytes) ->
                assertEquals("Resume cannot select another work identity", original.canonicalFile, plans.single().canonicalFile)
                assertTrue("Resume must preserve the original frozen decision", bytes.contentEquals(original.readBytes()))
                report.put("recovery_resumed_same_frozen_plan", true)
            }
            val plan = plans.singleOrNull()?.let { JSONObject(it.readText()) }
            if (analysisBefore.isNotEmpty()) {
                analysisBefore.forEach { (relative, bytes) ->
                    assertTrue("Reused candidate bytes changed: $relative", File(work, relative).readBytes().contentEquals(bytes))
                }
                val checkpoint = requireNotNull(plan).getJSONObject("cost").getJSONObject("checkpoint")
                check(checkpoint.getInt("reused_candidates") >= analysisBefore.size)
                check((firstRestoredCount.get() ?: -1) >= analysisBefore.size) { "Concrete restored progress reset" }
                val saved = JSONObject(String(analysisBefore.values.last(), Charsets.UTF_8))
                    .getJSONObject("payload").getDouble("cumulative_elapsed_seconds")
                check(checkpoint.getDouble("saved_elapsed_seconds") + 1e-9 >= saved)
                report.put("analysis_resume", JSONObject().put("prior_prefix_bytes_identical", true)
                    .put("first_concrete_progress", firstRestoredCount.get()).put("checkpoint", checkpoint))
            }
            if (plan != null) {
                assertEquals(if (adPolicy == AdRemovalPolicy.WEIGHTED) WeightedHlsAds.POLICY
                    else EightMapleHlsAds.POLICY, plan.getString("policy"))
                report.put("skipped_ads", plan.getInt("skipped_ads"))
                    .put("skipped_seconds", plan.getDouble("skipped_ad_seconds"))
                    .put("coverage", plan.optJSONObject("coverage"))
                    .put("cost", plan.getJSONObject("cost"))
            } else report.put("skipped_ads", 0).put("skipped_seconds", 0)
            if (adPolicy != AdRemovalPolicy.WEIGHTED) {
                assertFalse("Fast/off must not run weighted analysis", work.walkTopDown()
                    .any { it.isFile && it.name == "weighted-features.json" })
            }
            // Results are evidence, including a partial/missed/false-positive result.
            // Do not delete that evidence because an expected-count assertion fails.
            copyEvidence(work, evidence)
            val copy = File(evidence, "published.mp4")
            context.contentResolver.openInputStream(publication)!!.use { input ->
                copy.outputStream().use { outputStream -> input.copyTo(outputStream) }
            }
            report.put("published_bytes", copy.length()).put("published_sha256", sha256(copy))
            assertTrue("Output must be nonempty", copy.length() > 0)
            args.getString("qaWeightedExpectedAds")?.let { expected ->
                assertEquals("Autonomous selected-range count", expected.toInt(), report.getInt("skipped_ads"))
            }
            report.put("test_status", "completed")
            completed = true
        } catch (error: Throwable) {
            copyEvidence(work, evidence)
            report.put("test_status", "failed").put("error", error.toString())
            if (error.message == "QA_OPERATOR_ABORT") report.put("operator_abort", true)
            throw error
        } finally {
            report.put("elapsed_ms", System.currentTimeMillis() - started)
            try {
                publication?.let { uri ->
                    check(if (DocumentsContract.isDocumentUri(context, uri))
                        DocumentsContract.deleteDocument(context.contentResolver, uri)
                    else context.contentResolver.delete(uri, null, null) > 0) { "QA publication cleanup failed" }
                }
                if (recoveryId == null || completed) {
                    check(!work.exists() || work.deleteRecursively()) { "QA private work cleanup failed" }
                    report.put("cleanup", "own_publication_and_task_removed")
                } else report.put("cleanup", "preserved_interrupted_work")
            } finally {
                File(evidence, "result.json").writeText(report.toString(2))
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "QA_WEIGHTED_EVIDENCE=${evidence.absolutePath}\n")
                })
            }
        }
    }

    private fun copyEvidence(work: File, evidence: File) {
        if (!work.exists()) return
        val names = setOf("ad-input.json", "ad-catalog.json", "ad-plan.json", "weighted-features.json",
            "known-plan.json", "media-validation.json", "session.json", "checkpoint.json", "weighted-latest.json",
            "publication.json")
        work.walkTopDown().filter { it.isFile && it.name in names }.forEach { source ->
            source.copyTo(File(evidence, source.name), overwrite = true)
        }
        work.walkTopDown().filter { it.isDirectory && it.name == "analysis-checkpoint" && it.parentFile?.name == ".resume" }
            .forEach { source ->
                check(source.canonicalFile.toPath().startsWith(work.canonicalFile.toPath()))
                check(source.copyRecursively(File(evidence, source.relativeTo(work).path), overwrite = true))
            }
    }

    /** Snapshot only complete raw candidate records in the active binding. */
    private fun analysisPrefix(work: File): Map<String, ByteArray> {
        val storage = work.walkTopDown().filter { it.isDirectory && it.name == "analysis-checkpoint" &&
            it.parentFile?.name == ".resume" }.single()
        check(storage.canonicalFile.toPath().startsWith(work.canonicalFile.toPath()))
        val binding = File(storage, "active.json").readBytes()
        val directory = requireNotNull(storage.listFiles()).filter { it.isDirectory &&
            it.name.matches(Regex("[0-9a-f]{64}")) && File(it, "header.json").isFile &&
            File(it, "header.json").readBytes().contentEquals(binding) }.single()
        return requireNotNull(directory.listFiles()).filter { it.isFile && it.name.matches(Regex("\\d{6}\\.json")) }
            .sortedBy { it.name }.mapIndexed { ordinal, file ->
                check(file.name == "%06d.json".format(java.util.Locale.US, ordinal))
                val bytes = file.readBytes()
                val record = JSONObject(String(bytes, Charsets.UTF_8))
                check(record.getInt("ordinal") == ordinal)
                val payload = record.getJSONObject("payload")
                listOf("features", "weighted", "row").forEach { payload.getJSONObject(it) }
                file.relativeTo(work).path to bytes
            }.toMap()
    }

    private fun prefixHashes(work: File, prefix: Map<String, ByteArray>) = JSONObject().apply {
        prefix.keys.forEach { relative -> put(relative, sha256(File(work, relative))) }
    }

    private fun directFixture(url: String, id: String): MediaItem {
        val kind = InstrumentationRegistry.getArguments().getString("qaFixtureSourceKind")
            ?.let { SourceKind.valueOf(it) } ?: SourceKind.GIMY
        require(kind != SourceKind.GENERAL)
        val format = MediaFormat("qa-hls", "mp4", "QA HLS", null, true, true, false,
            null, MediaResource(url))
        return MediaItem(url, null, id, id, null, null, false, listOf(format),
            SourceContext(url, url, kind), DownloadTarget.DIRECT)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
