package com.changyow.mediadler.data.ytdlp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.download.AdAnalysisProgressAccumulator
import com.changyow.mediadler.core.model.AdRemovalPolicy
import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.EpisodePage
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.QueuedSpec
import com.changyow.mediadler.core.model.ShareMode
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import com.changyow.mediadler.core.repo.MediaExtractor
import com.changyow.mediadler.data.queue.SqliteDownloadQueueStore
import com.changyow.mediadler.data.settings.DataStoreSettingsRepository
import com.changyow.mediadler.download.DownloadQueue
import com.changyow.mediadler.ui.picker.PickerViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, disposable-emulator QA only: -e allowIsolatedAdQa true.
 * The real settings singleton is read/written and restored per test. Never run
 * on a user's emulator or physical device. Queue DBs and work files use UUID
 * namespaces. Only the separately opted-in renderProgressCardFixture uses the
 * actual empty app queue, on an offline emulator, to show explicitly synthetic
 * UI progress. No test starts services, resolves a URL, or downloads media.
 * Recreation here means new
 * repository/SQLite/queue instances; external process-death QA is separate.
 */
@RunWith(AndroidJUnit4::class)
class AdPolicyDeviceTest {
    private lateinit var isolated: IsolatedContext
    private lateinit var settings: DataStoreSettingsRepository
    private val stores = mutableListOf<SqliteDownloadQueueStore>()
    private var originalSettings: AppSettings? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun prepare() = runBlocking<Unit> {
        assumeTrue("Requires explicit owned disposable emulator authorization",
            InstrumentationRegistry.getArguments().getString("allowIsolatedAdQa") == "true")
        assumeTrue("Physical devices are excluded", Build.HARDWARE in setOf("ranchu", "goldfish"))
        isolated = IsolatedContext(instrumentation.targetContext)
        settings = DataStoreSettingsRepository(instrumentation.targetContext)
        originalSettings = withTimeout(10_000) { settings.settings.first() }
    }

    @After fun cleanup() = runBlocking<Unit> {
        try {
            originalSettings?.let { saved -> settings.update { saved } }
        } finally {
            try {
                // The production store has no close API. Only close helpers
                // belonging to instances created by this test, never appContainer.
                val helper = SqliteDownloadQueueStore::class.java.getDeclaredField("helper")
                    .apply { isAccessible = true }
                stores.forEach { (helper.get(it) as SQLiteOpenHelper).close() }
            } finally {
                if (::isolated.isInitialized) isolated.cleanup()
            }
        }
    }

    @Test fun threeModesPersistToDiskAndNewRepositoryReadsThem() = runBlocking<Unit> {
        val file = File(instrumentation.targetContext.filesDir, "datastore/settings.preferences_pb")
        val diskStates = mutableListOf<List<Byte>>()
        for (policy in AdRemovalPolicy.entries) {
            setPolicy(policy)
            val restored = DataStoreSettingsRepository(instrumentation.targetContext).settings.first()
            assertEquals(policy, restored.adRemovalPolicy)
            assertEquals(policy != AdRemovalPolicy.OFF, restored.adRemovalEnabled)
            // Disabled removal retains the weighted preference but has OFF precedence.
            assertEquals(policy != AdRemovalPolicy.CONFIRMED_ONLY, restored.weightedAdAnalysisEnabled)
            assertTrue("Committed preferences must exist on disk", file.isFile && file.length() > 0)
            diskStates += file.readBytes().toList()
        }
        assertEquals("All three persisted flag combinations must differ", 3, diskStates.toSet().size)
    }

    @Test fun sqlitePolicySnapshotSurvivesPauseRetryAndQueueRecreation() = runBlocking<Unit> {
        for ((index, policy) in AdRemovalPolicy.entries.withIndex()) {
            setPolicy(policy)
            var queue = queue()
            val id = queue.submitPages(listOf(page(index + 1)), FormatSelection.BestVideo,
                settings.settings.first().adRemovalPolicy).single()
            val first = requireNotNull(queue.claimNext())
            queue.resolved(first, request("resolved-$index", SourceKind.EIGHT_MAPLE))
            val partial = File(queue.workDirectory(id), "segment.part").apply {
                parentFile!!.mkdirs()
                writeText("retained-$policy")
            }
            setPolicy(if (policy == AdRemovalPolicy.OFF) AdRemovalPolicy.WEIGHTED else AdRemovalPolicy.OFF)
            assertTrue(queue.pauseTask(id))
            queue.acknowledgeStop(first, interrupted = true)

            queue = queue()
            assertEquals(DownloadStatus.PAUSED, queue.get(id)?.status)
            assertEquals(policy, storedPolicy(id))
            assertTrue(queue.resumeTask(id))
            val resumed = requireNotNull(queue.claimNext(preferResolved = true))
            assertEquals(policy, resumed.adRemovalPolicy)
            assertNotNull(resumed.resolvedRequest)
            assertEquals("retained-$policy", partial.readText())
            queue.fail(resumed, "test-only failure")

            queue = queue()
            assertTrue(queue.retry(id))
            val retried = requireNotNull(queue.claimNext(preferResolved = true))
            assertEquals(policy, retried.adRemovalPolicy)
            assertNull("Page retry must re-resolve, without changing policy", retried.resolvedRequest)
            assertEquals("retained-$policy", partial.readText())
            queue.fail(retried, "finished test attempt")
        }
    }

    @Test fun generalRequestKeepsItsOwnSnapshotWhenSettingsChange() = runBlocking<Unit> {
        val request = request("general").copy(adRemovalPolicy = AdRemovalPolicy.OFF)
        var queue = queue()
        val id = queue.submit(request)
        setPolicy(AdRemovalPolicy.WEIGHTED)
        queue = queue()
        val claim = requireNotNull(queue.claimNext())
        assertEquals(id, claim.id)
        assertEquals(AdRemovalPolicy.OFF, claim.adRemovalPolicy)
        assertEquals(request, (claim.spec as QueuedSpec.Resolved).request)
        assertEquals(AdRemovalPolicy.OFF, storedPolicy(id))
    }

    @Test fun legacyFreshPayloadDefaultsToReliableBeforeClaim() {
        var queue = queue()
        val id = queue.submitPages(listOf(page(40)), FormatSelection.BestVideo,
            AdRemovalPolicy.WEIGHTED).single()
        removeSerializedPolicy(id)
        queue = queue()
        assertEquals(AdRemovalPolicy.CONFIRMED_ONLY, storedPolicy(id))
        assertEquals(AdRemovalPolicy.CONFIRMED_ONLY, requireNotNull(queue.claimNext()).adRemovalPolicy)
    }

    @Test fun legacyPartialPayloadCannotResumeAndRetainsOriginalBytes() {
        var queue = queue()
        val id = queue.submitPages(listOf(page(41)), FormatSelection.BestVideo,
            AdRemovalPolicy.WEIGHTED).single()
        val partial = File(queue.workDirectory(id), ".resume/segment.ts").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0x47, 1, 2, 3, 4))
        }
        val original = partial.readBytes()
        removeSerializedPolicy(id)
        queue = queue()
        assertNull(storedPolicy(id))
        assertEquals(DownloadStatus.PAUSED, queue.get(id)?.status)
        assertTrue(requireNotNull(queue.get(id)?.progressDetail).contains("請重新建立下載"))
        assertFalse(queue.resumeTask(id))
        assertFalse(queue.retry(id))
        assertNull(queue.claimNext())
        assertArrayEquals(original, partial.readBytes())
        assertNull("Repeated recreation must not choose a policy", queue().claimNext())
        assertArrayEquals(original, partial.readBytes())
    }

    @Test fun incompatibleExecutionIdentityRetainsFrozenPlanAndPartial() {
        val work = File(isolated.workRoot, "incompatible-runtime").apply { mkdirs() }
        val partial = File(work, "media.part").apply { writeText("original-partial") }
        val plan = File(work, "ad-plan.json").apply { writeText("test-only-frozen-plan") }
        AdWorkPolicy.requireCompatible(work, "WEIGHTED\nruntime-a")
        for (changed in listOf("OFF", "CONFIRMED_ONLY", "WEIGHTED\nruntime-b")) {
            try {
                AdWorkPolicy.requireCompatible(work, changed)
                fail("Incompatible identity must stop before changing work")
            } catch (_: IllegalStateException) {
                assertEquals("WEIGHTED\nruntime-a", File(work, "ad-execution-policy.txt").readText())
                assertEquals("original-partial", partial.readText())
                assertEquals("test-only-frozen-plan", plan.readText())
            }
        }
    }

    @Test fun sharedGeneralOneTapKeepsSnapshotTakenBeforeExtraction() = runBlocking<Unit> {
        setPolicy(AdRemovalPolicy.WEIGHTED)
        settings.update { it.copy(shareMode = ShareMode.ONE_TAP) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val extractor = object : MediaExtractor {
            override suspend fun extract(url: String): Result<List<MediaItem>> {
                entered.complete(Unit)
                release.await()
                return Result.success(listOf(request("shared").item))
            }
        }
        val queue = queue()
        val viewModels = ViewModelStore()
        lateinit var picker: PickerViewModel
        instrumentation.runOnMainSync {
            picker = PickerViewModel("https://example.test/shared", extractor, settings, queue)
            viewModels.put("isolated-policy-picker", picker)
        }
        try {
            withTimeout(10_000) { entered.await() }
            setPolicy(AdRemovalPolicy.OFF)
            release.complete(Unit)
            val submitted = withTimeout(10_000) { picker.state.first { it is PickerViewModel.UiState.Submitted } }
            assertEquals(1, (submitted as PickerViewModel.UiState.Submitted).count)
            val claim = requireNotNull(queue().claimNext())
            assertEquals(AdRemovalPolicy.WEIGHTED, claim.adRemovalPolicy)
            assertEquals(AdRemovalPolicy.OFF, settings.settings.first().adRemovalPolicy)
        } finally {
            release.complete(Unit)
            instrumentation.runOnMainSync { viewModels.clear() }
            withTimeout(10_000) { picker.viewModelScope.coroutineContext[Job]?.join() }
        }
    }

    /** Render-only fixture, NOT evidence of actual analysis or elapsed work. */
    @Test fun renderProgressCardFixture() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("UI fixture requires separate opt-in", args.getString("seedUiProgress") == "true")
        val observationSeconds = args.getString("qaUiObservationSeconds")?.toLong() ?: 60L
        require(observationSeconds in 0..120)
        val container = instrumentation.targetContext.appContainer
        assumeTrue("Keep render-only fixture offline to exclude automatic recovery work",
            !container.downloadNetwork.available.value)
        val queue = container.downloadQueue
        check(queue.tasks.value.isEmpty()) { "UI fixture requires an empty disposable queue" }
        val fixture = request("qa-ui-${UUID.randomUUID()}").let {
            it.copy(item = it.item.copy(title = "UI 排版測試：合成進度，非實際分析"),
                adRemovalPolicy = AdRemovalPolicy.WEIGHTED)
        }
        val id = queue.submit(fixture)
        val claim = queue.claimNext()
        try {
            check(claim?.id == id) { "UI fixture must claim only its own task" }
            val ownedClaim = requireNotNull(claim)
            val progress = AdAnalysisProgressAccumulator { 0L }
            queue.setProgress(ownedClaim, requireNotNull(progress.accept(
                "HLS_AD_PROGRESS {\"stage\":\"ocr\",\"completed\":4,\"total\":12,\"unknown\":2,\"elapsed_seconds\":150}")))
            // A new probe stage must retain all cumulative fields on the card.
            queue.setProgress(ownedClaim, requireNotNull(progress.accept("HLS_AD_PROGRESS {\"stage\":\"probe\"}")))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "QA_UI_FIXTURE_PROGRESS_READY=$id (synthetic, not real analysis)\n")
            })
            delay(observationSeconds * 1000)
            if (args.getString("seedUiLegacy") == "true") {
                // Test-only projection of the same owned record, not a migration
                // performed against persisted user data. Cleanup uses its claim.
                val detail = "舊任務的去 AD 策略無法確認，已保留暫存；請重新建立下載，原任務可保留或刪除"
                val field = DownloadQueue::class.java.getDeclaredField("_tasks").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val projection = field.get(queue) as MutableStateFlow<List<DownloadTask>>
                check(projection.value.single().id == id)
                projection.value = projection.value.map { it.copy(status = DownloadStatus.PAUSED,
                    progressDetail = detail, errorMessage = detail, adPolicyMigrationRequired = true) }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "QA_UI_FIXTURE_LEGACY_READY=$id (synthetic projection)\n")
                })
                delay(observationSeconds * 1000)
            }
        } finally {
            queue.remove(id)
            claim?.takeIf { it.id == id }?.let { queue.acknowledgeStop(it, interrupted = true) }
            assertNull("Only the owned UI fixture must be removed", queue.get(id))
        }
    }

    private suspend fun setPolicy(policy: AdRemovalPolicy) = settings.update {
        it.copy(adRemovalEnabled = policy != AdRemovalPolicy.OFF,
            weightedAdAnalysisEnabled = policy != AdRemovalPolicy.CONFIRMED_ONLY)
    }

    private fun store() = SqliteDownloadQueueStore(isolated).also { stores += it }

    private fun queue() = DownloadQueue(store(), isolated.workRoot)

    private fun storedPolicy(id: String) = store().read()
        .single { it.task.id == id }.adRemovalPolicy

    /** Remove the actual wire field to exercise old payload deserialization. */
    private fun removeSerializedPolicy(id: String) {
        isolated.openOrCreateDatabase("download_queue.db", Context.MODE_PRIVATE, null).use { db ->
            val payload = db.query("download_queue", arrayOf("payload"), "id = ?", arrayOf(id),
                null, null, null).use { cursor ->
                check(cursor.moveToFirst())
                JSONObject(cursor.getString(0)).apply { remove("adRemovalPolicy") }.toString()
            }
            db.execSQL("UPDATE download_queue SET payload = ? WHERE id = ?", arrayOf(payload, id))
        }
    }

    private fun page(index: Int) = EpisodePage("https://8maple.st/play/QA123-1-$index.html",
        SourceKind.EIGHT_MAPLE, title = "Isolated policy $index")

    private fun request(id: String, kind: SourceKind = SourceKind.GENERAL): DownloadRequest {
        val url = if (kind == SourceKind.GENERAL) "https://example.test/$id" else page(1).playbackUrl
        return DownloadRequest(MediaItem(url, null, id, "Test $id", null, null, false, emptyList(),
            source = SourceContext(url, url, kind)), FormatSelection.BestVideo)
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "ad-policy-qa-${UUID.randomUUID()}-"
        private val root = File(base.cacheDir, prefix).apply { check(mkdir()) }
        val workRoot = File(root, "work").apply { check(mkdir()) }
        private val databases = mutableListOf<SQLiteDatabase>()
        private val databaseNames = mutableSetOf<String>()
        private val preferenceNames = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File {
            databaseNames += prefix + name
            return baseContext.getDatabasePath(prefix + name)
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
            baseContext.openOrCreateDatabase(prefix + name, mode, factory).also {
                databaseNames += prefix + name
                databases += it
            }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?) =
            baseContext.openOrCreateDatabase(prefix + name, mode, factory, errorHandler).also {
                databaseNames += prefix + name
                databases += it
            }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferenceNames += prefix + name
            return baseContext.getSharedPreferences(prefix + name, mode)
        }

        fun cleanup() {
            databases.filter { it.isOpen }.forEach { it.close() }
            databaseNames.forEach { check(baseContext.deleteDatabase(it)) }
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            check(root.deleteRecursively())
        }
    }
}
