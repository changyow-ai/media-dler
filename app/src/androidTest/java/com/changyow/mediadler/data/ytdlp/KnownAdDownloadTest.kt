package com.changyow.mediadler.data.ytdlp

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.DownloadPhase
import com.changyow.mediadler.core.model.DownloadProgress
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.DownloadTarget
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaFormat
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.MediaResource
import com.changyow.mediadler.core.model.SourceContext
import com.changyow.mediadler.core.model.SourceKind
import com.changyow.mediadler.core.repo.DownloadOutput
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in native integration tests with real media, NOT live 8maple extraction.
 * Serve the authorized fixtures on the host and expose them with adb reverse.
 * Required instrumentation arguments:
 *   adFixtureUrl / noAdFixtureUrl: controlled http://127.0.0.1:<port>/....mp4 URLs
 *   adFixtureSha256 / noAdFixtureSha256: full fixture SHA256 hex values
 *   adFixtureRemovedAds: expected positive-fixture matches (default 1; original E11 uses 2)
 * The positive fixture must preserve the full catalogued ad payload bytes;
 * remuxing an excerpt can change interleaving and invalidate the exact fingerprint.
 * The negative fixture must contain no catalogued ad. Missing arguments skip with
 * an explicit assumption. No fixtures or credentials are committed to the APK.
 *
 * HLS-only cases require hlsJoinOneUrl / hlsJoinTwoUrl (localhost .m3u8 URLs) and
 * hlsJoinOneSeconds / hlsJoinTwoSeconds (sum of four retained EXTINF durations).
 * Each playlist contains two retained TS segments, a marked v1 block with seven
 * real, fingerprinted ad TS references, a closing discontinuity, and two retained TS segments.
 * Ads must be fetched for content verification; retained candidate bytes are reused.
 * New content cases use hlsContentCasesUrl / hlsFullSnapshotUrl / hlsFixtureStateUrl.
 * Content-case seconds are independently probed retained AV duration, including
 * audio that can extend beyond the provider's video EXTINF duration.
 * Optional hlsEvidenceDirectory=qa_ad_hls_<UUID> retains exact published MP4 copies
 * under target externalFilesDir for host pull and explicit host removal afterwards.
 * Evidence files are unique and never overwritten; normal test cleanup retains them.
 * Missing HLS arguments skip only HLS cases; missing MP4 arguments skip only MP4 cases.
 *
 * Calls the public application downloader directly, using the user's existing
 * destination setting. Does not enqueue tasks, write history, or change preferences.
 * Deletes only returned test URIs, unique task directories, and private test copies,
 * after awaited native completion/cancellation acknowledgement.
 */
@RunWith(AndroidJUnit4::class)
class KnownAdDownloadTest {
    private lateinit var context: Context
    private lateinit var privateCopies: File
    private lateinit var adFixture: Fixture
    private lateinit var noAdFixture: Fixture
    private var expectedRemovedAds = 1
    private val publishedUris = CopyOnWriteArrayList<Uri>()
    private val taskIds = mutableListOf<String>()

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        privateCopies = File(context.cacheDir, "qa_ad_${UUID.randomUUID()}")
        check(privateCopies.mkdirs()) { "Could not create private fixture directory" }
    }

    private suspend fun prepareMp4Fixtures() {
        val args = InstrumentationRegistry.getArguments()
        val required = listOf("adFixtureUrl", "noAdFixtureUrl", "adFixtureSha256", "noAdFixtureSha256")
        assumeTrue("Known-ad MP4 QA requires controlled fixtures: ${required.joinToString()}",
            required.all { !args.getString(it).isNullOrBlank() })
        expectedRemovedAds = args.getString("adFixtureRemovedAds")?.let {
            requireNotNull(it.toIntOrNull()) { "adFixtureRemovedAds must be an integer" }
        } ?: 1
        require(expectedRemovedAds in 1..16) { "adFixtureRemovedAds must be between 1 and 16" }
        adFixture = fixture(args.getString("adFixtureUrl")!!, args.getString("adFixtureSha256")!!)
        noAdFixture = fixture(args.getString("noAdFixtureUrl")!!, args.getString("noAdFixtureSha256")!!)
        initializeEngine()
    }

    private suspend fun initializeEngine() {
        val state = withTimeout(120_000) { context.appContainer.engine.ensureInit() }
        assertTrue("Bundled native engine initialization failed: $state", state is EngineState.Ready)
    }

    @After fun tearDown() {
        if (!::context.isInitialized) return
        val failures = mutableListOf<String>()
        publishedUris.forEach { uri ->
            try {
                val deleted = if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else context.contentResolver.delete(uri, null, null) > 0
                if (!deleted) failures += "Could not delete test output $uri"
            } catch (error: Exception) {
                failures += "Could not delete test output $uri: ${error.javaClass.simpleName}"
            }
        }
        // These are UUID task IDs created by this test instance, never user task IDs.
        taskIds.forEach { id ->
            val directory = taskDirectory(id)
            if (directory.exists() && !directory.deleteRecursively()) failures += "Could not delete test task $id"
        }
        if (::privateCopies.isInitialized && privateCopies.exists() && !privateCopies.deleteRecursively()) {
            failures += "Could not delete private test copies"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun eightMapleRemovesVerifiedAdsBeforePublicationAndDoesNotCutTwice() = runBlocking {
        prepareMp4Fixtures()
        val baseline = fetchFixture(adFixture, "positive-input.mp4")
        val id = newTask()
        val progress = CopyOnWriteArrayList<DownloadProgress>()
        val output = download(adFixture, SourceKind.EIGHT_MAPLE, id, progress::add)
        assertCleanedProgress(progress)
        val published = inspect(output)
        assertTrue("Cleaned publication must be smaller than the verified fixture", published.size < baseline.size)
        assertNotEquals("The ad-bearing fixture must not be published unchanged", baseline.sha256, published.sha256)
        assertVerifiedPublication(id, output)

        val copy = File(privateCopies, "published-copy.mp4")
        context.contentResolver.openInputStream(Uri.parse(output.uri))!!.use { input ->
            copy.outputStream().use { input.copyTo(it) }
        }
        val stages = CopyOnWriteArrayList<String>()
        val rescanned = withTimeout(DOWNLOAD_TIMEOUT) {
            context.appContainer.engine.cleanKnownAds(copy, stages::add)
        }
        assertEquals("A verified output must not be cut twice", 0, rescanned.removedAds)
        assertEquals(0.0, rescanned.removedSeconds, 0.0)
        assertEquals(copy.canonicalFile, rescanned.file.canonicalFile)
        assertEquals(listOf("scan"), stages.toList())
        copy.inputStream().use { assertEquals(published, digest(it)) }
        report("positive", published, progress)
    }

    @Test fun noKnownAdPublishesExactInputBytes() = runBlocking {
        prepareMp4Fixtures()
        val baseline = fetchFixture(noAdFixture, "negative-input.mp4")
        val id = newTask()
        val progress = CopyOnWriteArrayList<DownloadProgress>()
        val output = download(noAdFixture, SourceKind.EIGHT_MAPLE, id, progress::add)
        assertTrue(progress.any { it.phase == DownloadPhase.PROCESSING })
        assertTrue(progress.any { it.phase == DownloadPhase.SAVING })
        assertFalse(progress.any { it.phase == DownloadPhase.SAVING && it.detail.orEmpty().contains("已移除") })
        val published = inspect(output)
        assertEquals("No-match publication must preserve every fixture byte", baseline, published)
        assertEquals(noAdFixture.sha256, published.sha256)
        assertVerifiedPublication(id, output)
        report("no-match", published, progress)
    }

    @Test fun validationCancellationAcknowledgesCleanupAndSameTaskRetryRemovesVerifiedAds() = runBlocking {
        prepareMp4Fixtures()
        fetchFixture(adFixture, "cancel-input.mp4")
        val id = newTask()
        val request = request(adFixture, SourceKind.EIGHT_MAPLE, id)
        val firstProgress = CopyOnWriteArrayList<DownloadProgress>()
        val validationEntered = CompletableDeferred<Unit>()
        val releaseCallback = CountDownLatch(1)
        coroutineScope {
            val attempt = async(Dispatchers.IO) {
                withTimeout(DOWNLOAD_TIMEOUT) {
                    context.appContainer.downloader.download(request, id) { progress ->
                        firstProgress.add(progress)
                        if (progress.phase == DownloadPhase.PROCESSING && progress.detail == VALIDATION_DETAIL &&
                            validationEntered.complete(Unit)) {
                            check(releaseCallback.await(10, TimeUnit.SECONDS)) { "Validation cancellation barrier timed out" }
                        }
                    }.getOrThrow().also(::track)
                }
            }
            try {
                withTimeout(DOWNLOAD_TIMEOUT) { validationEntered.await() }
                assertFalse("No publication journal may exist at validation", publication(id).exists())
                assertFalse(firstProgress.any { it.phase == DownloadPhase.SAVING })
                attempt.cancel(CancellationException("Known-ad QA cancellation at validation"))
                releaseCallback.countDown()
                withTimeout(DOWNLOAD_TIMEOUT) { attempt.join() }
                assertTrue("The initial attempt must acknowledge cancellation", attempt.isCancelled)
            } finally {
                releaseCallback.countDown()
                // Never delete staged/native-owned files while the execution still owns them.
                withContext(NonCancellable) { attempt.cancelAndJoin() }
            }
        }
        assertFalse("Cancelled validation must not create publication.json", publication(id).exists())
        assertFalse("Cancelled validation must not reach publication", firstProgress.any { it.phase == DownloadPhase.SAVING })
        assertTrue("Cancelled cleaner must remove its own staged outputs after native exit",
            taskDirectory(id).walkTopDown().none { it.isDirectory && it.name.startsWith(".ad-cleanup-") })
        assertTrue("Cancelled attempt must not return a published URI", publishedUris.isEmpty())

        val retryProgress = CopyOnWriteArrayList<DownloadProgress>()
        val retry = download(adFixture, SourceKind.EIGHT_MAPLE, id, retryProgress::add)
        assertCleanedProgress(retryProgress)
        assertTrue("Retry must perform validation instead of reusing an unverified output",
            retryProgress.any { it.detail == VALIDATION_DETAIL })
        val published = inspect(retry)
        assertNotEquals(adFixture.sha256, published.sha256)
        assertVerifiedPublication(id, retry)
        report("cancel-and-retry", published, retryProgress)
    }

    @Test fun generalSourcePreservesAdBearingFixtureBytes() = runBlocking {
        prepareMp4Fixtures()
        val baseline = fetchFixture(adFixture, "general-input.mp4")
        val id = newTask()
        val progress = CopyOnWriteArrayList<DownloadProgress>()
        val output = download(adFixture, SourceKind.GENERAL, id, progress::add)
        val published = inspect(output)
        assertEquals("SourceKind.GENERAL must preserve the ad-bearing fixture", baseline, published)
        assertEquals(adFixture.sha256, published.sha256)
        assertFalse("General sources must not invoke known-ad processing",
            progress.any { it.phase == DownloadPhase.PROCESSING && it.detail in CLEANUP_DETAILS })
        assertTrue(progress.any { it.phase == DownloadPhase.SAVING })
        assertVerifiedPublication(id, output)
        report("general-gate", published, progress)
    }

    @Test fun verifiedMarkedHlsBothJoinsPublishDecodableAudioVideo() = runBlocking {
        val fixtures = prepareHlsFixtures()
        fixtures.forEachIndexed { index, fixture ->
            val id = newTask()
            val progress = CopyOnWriteArrayList<DownloadProgress>()
            val output = downloadHls(fixture, id, progress::add)
            assertHlsProgress(progress)
            val state = assertFilteredHlsState(id, 4)
            assertVerifiedPublication(id, output)
            assertDecodableHls(output, fixture, state)
            val published = inspect(output)
            saveHlsEvidence("join-${index + 1}", output, published)
            report("hls-join-${index + 1}", published, progress)
        }
    }

    @Test fun markedHlsCommittedFragmentCancellationAndSameTaskRetryPreserveFilteredSession() = runBlocking {
        val fixture = prepareHlsFixtures().first()
        val id = newTask()
        val firstProgress = CopyOnWriteArrayList<DownloadProgress>()
        val committed = CompletableDeferred<HlsState>()
        val releaseCallback = CountDownLatch(1)
        val interrupted = coroutineScope {
            val attempt = async(Dispatchers.IO) {
                downloadHls(fixture, id) { progress ->
                    firstProgress.add(progress)
                    if (progress.phase == DownloadPhase.DOWNLOADING && !committed.isCompleted) {
                        val state = hlsState(id)
                        if (state != null && state.index in 1..3 && committed.complete(state)) {
                            check(releaseCallback.await(10, TimeUnit.SECONDS)) { "HLS cancellation barrier timed out" }
                        }
                    }
                }
            }
            try {
                val state = withTimeout(DOWNLOAD_TIMEOUT) { committed.await() }
                assertFalse("Committed partial HLS must not have a publication journal", publication(id).exists())
                attempt.cancel(CancellationException("Known-ad QA cancellation after committed HLS fragment"))
                releaseCallback.countDown()
                withTimeout(DOWNLOAD_TIMEOUT) { attempt.join() }
                assertTrue("HLS cancellation must be acknowledged before examining partials", attempt.isCancelled)
                state
            } finally {
                releaseCallback.countDown()
                withContext(NonCancellable) { attempt.cancelAndJoin() }
            }
        }
        assertFalse("Interrupted HLS must not publish temporaries", publication(id).exists())
        assertFalse(firstProgress.any { it.phase == DownloadPhase.SAVING })
        assertTrue("Interrupted HLS must not return a published URI", publishedUris.isEmpty())
        val preserved = requireNotNull(hlsState(id)) { "Committed HLS checkpoint was not preserved" }
        assertEquals(interrupted.session, preserved.session)
        assertEquals(interrupted.identity, preserved.identity)
        assertEquals(interrupted.manifest, preserved.manifest)
        assertTrue("Only fully committed fragments may survive cancellation", preserved.index in interrupted.index..3)
        assertFilteredHlsState(id, preserved.index)

        val retryProgress = CopyOnWriteArrayList<DownloadProgress>()
        val output = downloadHls(fixture, id, retryProgress::add)
        assertHlsProgress(retryProgress)
        val completed = assertFilteredHlsState(id, 4)
        assertEquals("Retry must reuse the same filtered session", preserved.session, completed.session)
        assertEquals(preserved.identity, completed.identity)
        assertEquals(preserved.manifest, completed.manifest)
        assertVerifiedPublication(id, output)
        assertDecodableHls(output, fixture, completed)
        val published = inspect(output)
        saveHlsEvidence("join-1-retry", output, published)
        report("hls-cancel-and-retry", published, retryProgress)
    }

    private suspend fun prepareHlsFixtures(): List<HlsFixture> {
        val args = InstrumentationRegistry.getArguments()
        val names = listOf("hlsJoinOneUrl", "hlsJoinTwoUrl", "hlsJoinOneSeconds", "hlsJoinTwoSeconds")
        assumeTrue("Known-ad HLS QA requires controlled fixtures: ${names.joinToString()}",
            names.all { !args.getString(it).isNullOrBlank() })
        val fixtures = listOf("One", "Two").map { name ->
            val url = args.getString("hlsJoin${name}Url")!!
            controlledUrl(url, ".m3u8")
            val seconds = requireNotNull(args.getString("hlsJoin${name}Seconds")!!.toDoubleOrNull()) {
                "HLS retained duration must be numeric"
            }
            require(seconds.isFinite() && seconds > 4 && seconds <= 120) { "Use a bounded four-segment HLS join fixture" }
            HlsFixture(url, seconds)
        }
        initializeEngine()
        return fixtures
    }

    private suspend fun downloadHls(
        fixture: HlsFixture,
        id: String,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutput = withTimeout(DOWNLOAD_TIMEOUT) {
        context.appContainer.downloader.download(request(fixture.url, SourceKind.EIGHT_MAPLE, id), id, onProgress)
            .getOrThrow().also(::track)
    }

    private fun assertHlsProgress(progress: List<DownloadProgress>) {
        val downloading = progress.first { it.phase == DownloadPhase.DOWNLOADING && it.detail == "已辨識 1 段廣告，下載時略過" }
        assertEquals("已辨識 1 段廣告，下載時略過", downloading.detail)
        assertEquals(true, downloading.canResume)
        assertEquals("已略過 1 段廣告，正在儲存影片", progress.first { it.phase == DownloadPhase.SAVING }.detail)
    }

    private fun hlsState(id: String): HlsState? {
        val sessions = taskDirectory(id).walkTopDown()
            .filter { it.isFile && it.name == "session.json" && it.parentFile?.name == ".resume" }.toList()
        assertTrue("One stable filtered HLS task must have at most one session", sessions.size <= 1)
        val session = sessions.singleOrNull() ?: return null
        val checkpoint = File(session.parentFile, "checkpoint.json")
        if (!checkpoint.isFile) return null
        val spec = JSONObject(session.readText())
        val record = JSONObject(checkpoint.readText())
        assertEquals(1, spec.getInt("schema"))
        assertEquals(1, record.getInt("schema"))
        assertEquals("Checkpoint must belong to filtered snapshot", spec.getString("identity"), record.getString("identity"))
        return HlsState(session.canonicalFile, spec.getString("identity"), spec.getString("manifest"), record.getInt("index"))
    }

    private fun assertFilteredHlsState(id: String, expectedIndex: Int): HlsState {
        val state = requireNotNull(hlsState(id)) { "HLS checkpoint/session is missing" }
        assertTrue(state.identity.matches(Regex("[0-9a-f]{64}")))
        assertEquals(expectedIndex, state.index)
        val uris = state.manifest.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        assertEquals("Only four retained drama segments may reach the native adapter", 4, uris.size)
        assertEquals(4, retainedDurations(state).size)
        return state
    }

    @Test fun fullUnmarkedEpisodeIdentifiesAllFourConfirmedAdsBeforeMediaDownload() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("hlsFullSnapshotUrl")
        assumeTrue("Full snapshot QA requires hlsFullSnapshotUrl", !url.isNullOrBlank())
        controlledUrl(url!!, ".m3u8")
        initializeEngine()
        val id = newTask()
        val recognized = CompletableDeferred<Unit>()
        val barrier = CountDownLatch(1)
        coroutineScope {
            val attempt = async(Dispatchers.IO) {
                withTimeout(DOWNLOAD_TIMEOUT) {
                    context.appContainer.downloader.download(request(url, SourceKind.EIGHT_MAPLE, id,
                        mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://play.8maple.st/play/wbYC-1-1.html")), id) {
                        if (it.detail == "已辨識 4 段廣告，下載時略過" && recognized.complete(Unit)) {
                            check(barrier.await(10, TimeUnit.SECONDS))
                        }
                    }.getOrThrow().also(::track)
                }
            }
            try {
                withTimeout(DOWNLOAD_TIMEOUT) { recognized.await() }
                val plan = contentPlan(id)
                assertEquals(4, plan.getInt("skipped_ads"))
                assertEquals(18, plan.getInt("skipped_ad_segments"))
                assertEquals(68.266664, plan.getDouble("skipped_ad_seconds"), 0.001)
                assertFalse(publication(id).exists())
                savePlanEvidence("full-episode-four-ads", id)
                attempt.cancel(CancellationException("QA stops after complete native recognition"))
                barrier.countDown()
                withTimeout(DOWNLOAD_TIMEOUT) { attempt.join() }
                assertTrue(attempt.isCancelled)
            } finally {
                barrier.countDown()
                withContext(NonCancellable) { attempt.cancelAndJoin() }
            }
        }
        assertFalse(publication(id).exists())
        assertTrue(publishedUris.isEmpty())
    }

    @Test fun verifiedContentSkipsOnlyKnownAdsAndPreservesUnknownMedia() = runBlocking {
        for (fixture in contentFixtures().filter { it.label !in setOf("candidate-cancel", "checkpoint-cancel") }) {
            val id = newTask()
            val progress = CopyOnWriteArrayList<DownloadProgress>()
            val output = contentDownload(fixture, id, progress::add)
            saveHlsEvidence(fixture.label, output, inspect(output))
            savePlanEvidence(fixture.label, id)
            assertContentResult(fixture, id, output, progress)
        }
    }

    @Test fun candidateReadCancellationAndSameTaskRetryNeverPublishPartialPlans() = runBlocking {
        val fixture = contentFixtures().first { it.label == "candidate-cancel" }
        val stateUrl = InstrumentationRegistry.getArguments().getString("hlsFixtureStateUrl")
        assumeTrue("Candidate cancellation QA requires hlsFixtureStateUrl", !stateUrl.isNullOrBlank())
        val id = newTask()
        coroutineScope {
            val attempt = async(Dispatchers.IO) { contentDownload(fixture, id) {} }
            try {
                withTimeout(DOWNLOAD_TIMEOUT) {
                    while (!fixtureJson(stateUrl!!).optBoolean("slow_entered")) delay(50)
                }
                attempt.cancel(CancellationException("QA interrupts candidate body"))
                withTimeout(DOWNLOAD_TIMEOUT) { attempt.join() }
                assertTrue(attempt.isCancelled)
            } finally { withContext(NonCancellable) { attempt.cancelAndJoin() } }
        }
        assertFalse(publication(id).exists())
        assertTrue("Interrupted scan must not freeze a partial decision",
            taskDirectory(id).walkTopDown().none { it.name == "ad-plan.json" })
        val progress = CopyOnWriteArrayList<DownloadProgress>()
        val output = contentDownload(fixture, id, progress::add)
        assertContentResult(fixture, id, output, progress)
        saveHlsEvidence("candidate-retry", output, inspect(output))
        savePlanEvidence("candidate-retry", id)
    }

    @Test fun verifiedContentCheckpointRetryUsesSameFrozenDecision() = runBlocking {
        val fixture = contentFixtures().first { it.label == "checkpoint-cancel" }
        val id = newTask()
        val committed = CompletableDeferred<HlsState>()
        val barrier = CountDownLatch(1)
        var removedTestCache = false
        val preserved = coroutineScope {
            val attempt = async(Dispatchers.IO) {
                contentDownload(fixture, id) {
                    if (!removedTestCache && it.detail == "已辨識 1 段廣告，下載時略過") {
                        val planFile = taskDirectory(id).walkTopDown().single { f -> f.name == "ad-plan.json" }
                        val cached = contentPlan(id).getJSONArray("cached_segments")
                        val entry = (0 until cached.length()).map(cached::getJSONObject)
                            .single { e -> e.getString("url").endsWith("keep-3.ts") }
                        val body = File(planFile.parentFile, entry.getString("path")).canonicalFile
                        assertTrue(body.toPath().startsWith(taskDirectory(id).canonicalFile.toPath()))
                        assertTrue("Remove only this test's final cached fragment to exercise network fallback", body.delete())
                        removedTestCache = true
                    }
                    val state = hlsState(id)
                    if (state != null && state.index in 1 until fixture.retained && committed.complete(state)) {
                        check(barrier.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            try {
                val state = withTimeout(DOWNLOAD_TIMEOUT) { committed.await() }
                attempt.cancel(CancellationException("QA interrupts committed content HLS"))
                barrier.countDown()
                withTimeout(DOWNLOAD_TIMEOUT) { attempt.join() }
                assertTrue(attempt.isCancelled)
                state
            } finally {
                barrier.countDown()
                withContext(NonCancellable) { attempt.cancelAndJoin() }
            }
        }
        assertFalse(publication(id).exists())
        val frozen = contentPlan(id).toString()
        val progress = CopyOnWriteArrayList<DownloadProgress>()
        val output = contentDownload(fixture, id, progress::add)
        assertEquals(frozen, contentPlan(id).toString())
        assertEquals(preserved.identity, requireNotNull(hlsState(id)).identity)
        assertContentResult(fixture, id, output, progress)
        saveHlsEvidence("checkpoint-retry", output, inspect(output))
        savePlanEvidence("checkpoint-retry", id)
    }

    private suspend fun contentFixtures(): List<ContentFixture> {
        val url = InstrumentationRegistry.getArguments().getString("hlsContentCasesUrl")
        assumeTrue("Content HLS QA requires hlsContentCasesUrl", !url.isNullOrBlank())
        val cases = fixtureJson(url!!).getJSONArray("cases")
        initializeEngine()
        return (0 until cases.length()).map { i ->
            val c = cases.getJSONObject(i)
            val fixtureUrl = c.getString("url")
            controlledUrl(fixtureUrl, ".m3u8")
            ContentFixture(c.getString("label"), fixtureUrl, c.getDouble("seconds"),
                c.getInt("ads"), c.getInt("retained_segments"))
        }
    }

    /** Evidence-only decoder baseline; does not replace any feature assertion. */
    @Test fun mediaFrameProbeDiagnostics() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("hlsFrameProbeCasesUrl")
        assumeTrue("Decoder baseline requires hlsFrameProbeCasesUrl", !url.isNullOrBlank())
        val cases = fixtureJson(url!!).getJSONArray("cases")
        val result = org.json.JSONArray()
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val label = item.getString("label")
            val row = JSONObject().put("label", label)
            result.put(row)
            try {
                val file = if (item.has("url")) {
                    fetchFixture(fixture(item.getString("url"), item.getString("sha256")), "frame-$i.mp4")
                    File(privateCopies, "frame-$i.mp4")
                } else {
                    val path = item.getString("path")
                    require(path.startsWith("/sdcard/Download/media-dler/drama/戀愛博士/E0001 ") && path.endsWith(".mp4"))
                    File(path)
                }
                row.put("file_size", file.length())
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    row.put("duration_ms", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
                    val options = org.json.JSONArray()
                    row.put("options", options)
                    for (option in listOf(MediaMetadataRetriever.OPTION_CLOSEST,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                        MediaMetadataRetriever.OPTION_NEXT_SYNC)) {
                        val frame = retriever.getFrameAtTime((item.getDouble("at_seconds") * 1_000_000).toLong(), option)
                        options.put(JSONObject().put("option", option).put("frame_returned", frame != null)
                            .put("width", frame?.width).put("height", frame?.height))
                        frame?.recycle()
                    }
                    val scaled = retriever.getScaledFrameAtTime((item.getDouble("at_seconds") * 1_000_000).toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST, 320, 180)
                    row.put("scaled_frame_returned", scaled != null)
                    row.put("scaled_width", scaled?.width).put("scaled_height", scaled?.height)
                    scaled?.recycle()
                } finally { retriever.release() }
            } catch (error: Exception) { row.put("error", error.toString()) }
        }
        val body = JSONObject().put("scope", "read-only Android system frame decoder baseline").put("results", result)
        val name = requireNotNull(InstrumentationRegistry.getArguments().getString("hlsEvidenceDirectory"))
        require(name.matches(Regex("qa_ad_hls_[0-9a-fA-F-]{36}")))
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), name).apply { mkdirs() }
        val copy = File(directory, "frame-probe-${UUID.randomUUID()}.json")
        check(copy.createNewFile())
        copy.writeText(body.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nNATIVE_FRAME_PROBE $body\n")
        })
    }

    private suspend fun fixtureJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        controlledUrl(url, ".json")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            assertEquals(200, connection.responseCode)
            val bytes = connection.inputStream.use { it.readBytes() }
            check(bytes.size <= 1024 * 1024)
            JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    private suspend fun contentDownload(fixture: ContentFixture, id: String,
        onProgress: (DownloadProgress) -> Unit): DownloadOutput = try {
        withTimeout(DOWNLOAD_TIMEOUT) {
            context.appContainer.downloader.download(request(fixture.url, SourceKind.EIGHT_MAPLE, id), id, onProgress)
                .getOrThrow().also(::track)
        }
    } finally {
        if (taskDirectory(id).walkTopDown().any { it.isFile && it.name == "ad-plan.json" }) {
            savePlanEvidence(fixture.label, id)
        }
    }

    private fun contentPlan(id: String): JSONObject = JSONObject(taskDirectory(id).walkTopDown()
        .single { it.isFile && it.name == "ad-plan.json" }.readText())

    private fun assertContentResult(fixture: ContentFixture, id: String, output: DownloadOutput,
        progress: List<DownloadProgress>) {
        val plan = contentPlan(id)
        // Preserve native classification diagnostics even when an assertion fails.
        savePlanEvidence(fixture.label, id)
        assertEquals(fixture.ads, plan.getInt("skipped_ads"))
        val state = requireNotNull(hlsState(id))
        assertEquals(fixture.retained, state.index)
        assertEquals(plan.getString("identity"), state.identity)
        assertEquals(plan.getString("manifest"), state.manifest)
        assertVerifiedPublication(id, output)
        val saving = progress.first { it.phase == DownloadPhase.SAVING }
        if (fixture.ads > 0) assertEquals("已略過 ${fixture.ads} 段廣告，正在儲存影片", saving.detail)
        else assertFalse(saving.detail.orEmpty().contains("已略過"))
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(output.uri))
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            val seconds = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() / 1000.0
            assertEquals(fixture.seconds, seconds, 0.25)
            // ASUS Android 9 also returns null for CLOSEST on the independent
            // original-TS reference. Sync frames decode on all three baselines.
            // Check both retained ends; host validation checks every AV packet
            // and fully decodes the exact publication copied from the device.
            for (position in listOf(0.5, seconds - 0.5)) {
                val frame = retriever.getFrameAtTime((position * 1_000_000).toLong(),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertNotNull("Retained video must decode at ${position}s", frame)
                frame?.recycle()
            }
        } finally { retriever.release() }
    }

    private fun savePlanEvidence(label: String, id: String) {
        val name = InstrumentationRegistry.getArguments().getString("hlsEvidenceDirectory") ?: return
        require(name.matches(Regex("qa_ad_hls_[0-9a-fA-F-]{36}")))
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), name).apply { mkdirs() }
        val file = File(directory, "${label}_${UUID.randomUUID()}-plan.json")
        check(file.createNewFile())
        file.writeText(contentPlan(id).toString())
    }

    private fun retainedDurations(state: HlsState) = state.manifest.lineSequence().map(String::trim)
        .filter { it.startsWith("#EXTINF:") }
        .map { it.substringAfter(':').substringBefore(',').toDouble() }.toList()

    private fun assertDecodableHls(output: DownloadOutput, fixture: HlsFixture, state: HlsState) {
        val durations = retainedDurations(state)
        assertEquals(fixture.seconds, durations.sum(), 0.001)
        val join = durations.take(2).sum()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(output.uri))
            assertEquals("Published HLS must retain video", "yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("Published HLS must retain audio", "yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            val seconds = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() / 1000.0
            assertTrue("HLS duration $seconds must match retained duration ${fixture.seconds}", abs(seconds - fixture.seconds) <= 0.25)
            for (position in listOf(join - 2, join + 2)) {
                assertTrue("Decode probe must lie inside retained media on each side of the join", position > 0 && position < seconds)
                val frame = retriever.getFrameAtTime((position * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull("Retained video must decode at ${position}s", frame)
                frame?.recycle()
            }
        } finally { retriever.release() }
    }

    private fun saveHlsEvidence(label: String, output: DownloadOutput, fingerprint: Fingerprint) {
        val name = InstrumentationRegistry.getArguments().getString("hlsEvidenceDirectory") ?: return
        require(name.matches(Regex("qa_ad_hls_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            "hlsEvidenceDirectory must be qa_ad_hls_<UUID>"
        }
        val external = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        val directory = File(external, name)
        require(directory.canonicalFile.parentFile == external && directory.canonicalFile.name == name) {
            "HLS evidence must stay inside the target externalFilesDir"
        }
        check(directory.isDirectory || directory.mkdirs()) { "Could not create opt-in HLS evidence directory" }
        val copy = File(directory, "${label}_${UUID.randomUUID()}.mp4")
        check(copy.createNewFile()) { "Refusing to overwrite HLS evidence" }
        try {
            context.contentResolver.openInputStream(Uri.parse(output.uri))!!.use { input ->
                copy.outputStream().use { input.copyTo(it) }
            }
            copy.inputStream().use { assertEquals("Evidence must preserve exact publication bytes", fingerprint, digest(it)) }
        } catch (error: Throwable) {
            copy.delete()
            throw error
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nKNOWN_AD_HLS_EVIDENCE path=${copy.absolutePath} sha256=${fingerprint.sha256} size=${fingerprint.size}\n")
        })
    }

    private suspend fun download(
        fixture: Fixture,
        kind: SourceKind,
        taskId: String,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutput = withTimeout(DOWNLOAD_TIMEOUT) {
        context.appContainer.downloader.download(request(fixture, kind, taskId), taskId, onProgress)
            .getOrThrow().also(::track)
    }

    private fun request(fixture: Fixture, kind: SourceKind, id: String): DownloadRequest =
        request(fixture.url, kind, id)

    private fun request(url: String, kind: SourceKind, id: String, headers: Map<String, String> = emptyMap()): DownloadRequest {
        val format = MediaFormat("qa-direct", "mp4", "QA fixture MP4", null, true, true, false,
            null, MediaResource(url, headers))
        return DownloadRequest(MediaItem(
            sourceUrl = url,
            playlistIndex = null,
            id = id,
            title = id,
            thumbnailUrl = null,
            durationSeconds = null,
            isImage = false,
            formats = listOf(format),
            source = SourceContext(url, url, kind),
            downloadTarget = DownloadTarget.DIRECT,
            seriesTitle = id,
        ), FormatSelection.SpecificFormat(format))
    }

    private fun assertCleanedProgress(progress: List<DownloadProgress>) {
        assertTrue("Expected native known-ad PROCESSING progress", progress.any { it.phase == DownloadPhase.PROCESSING })
        val saving = progress.indexOfFirst { it.phase == DownloadPhase.SAVING }
        assertTrue("Expected SAVING after native validation", saving >= 0)
        assertTrue("Native validation must precede publication", progress.take(saving).any { it.detail == VALIDATION_DETAIL })
        assertEquals("已移除 $expectedRemovedAds 段廣告，正在儲存影片", progress[saving].detail)
    }

    private fun assertVerifiedPublication(id: String, output: DownloadOutput) {
        val record = JSONObject(publication(id).readText())
        assertTrue("DownloadPublisher must finish publication", record.getBoolean("published"))
        assertTrue(record.getBoolean("copied"))
        assertEquals(output.uri, record.getString("uri"))
        val source = File(record.getString("sourcePath")).canonicalFile
        assertTrue("Publication source must be owned by this test task",
            source.toPath().startsWith(taskDirectory(id).canonicalFile.toPath()))
        assertTrue(source.isFile && source.length() > 0)
    }

    private fun track(output: DownloadOutput) { publishedUris.add(Uri.parse(output.uri)) }
    private fun newTask(): String = "qa_ad_${UUID.randomUUID()}".also(taskIds::add)
    private fun taskDirectory(id: String) = File(File(context.noBackupFilesDir, "download-work"), id)
    private fun publication(id: String) = File(taskDirectory(id), "publication.json")

    private fun inspect(output: DownloadOutput): Fingerprint {
        assertEquals("video/mp4", output.mimeType)
        return context.contentResolver.openInputStream(Uri.parse(output.uri))!!.use(::digest)
    }

    private suspend fun fetchFixture(fixture: Fixture, name: String): Fingerprint = withContext(Dispatchers.IO) {
        val file = File(privateCopies, name)
        val connection = URL(fixture.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        try {
            assertEquals("Controlled fixture server must return HTTP 200", 200, connection.responseCode)
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= MAX_FIXTURE_BYTES) { "Use a controlled MP4 fixture of at most 512 MiB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            file.inputStream().use(::digest).also {
                assertTrue("Fixture must be nonempty", it.size > 0)
                assertEquals("Host fixture must match the supplied full SHA256", fixture.sha256, it.sha256)
            }
        } finally { connection.disconnect() }
    }

    private fun digest(input: InputStream): Fingerprint {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
            size += count
        }
        return Fingerprint(size, hash.digest().joinToString("") { "%02x".format(it) })
    }

    private fun fixture(url: String, sha256: String): Fixture {
        controlledUrl(url, ".mp4")
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Fixture SHA256 must be 64 hex characters" }
        return Fixture(url, sha256.lowercase())
    }

    private fun controlledUrl(url: String, extension: String) {
        val parsed = URL(url)
        require(parsed.protocol == "http" && parsed.host in setOf("127.0.0.1", "localhost", "::1", "[::1]") &&
            parsed.userInfo == null && parsed.path.endsWith(extension, ignoreCase = true)) {
            "Use a controlled localhost HTTP $extension URL via adb reverse"
        }
    }

    private fun report(case: String, fingerprint: Fingerprint, progress: List<DownloadProgress>) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nKNOWN_AD_QA $case size=${fingerprint.size} sha256=${fingerprint.sha256}" +
                " phases=${progress.map { it.phase }.distinct().joinToString()}\n")
        })
    }

    private data class Fixture(val url: String, val sha256: String)
    private data class HlsFixture(val url: String, val seconds: Double)
    private data class ContentFixture(val label: String, val url: String, val seconds: Double, val ads: Int, val retained: Int)
    private data class HlsState(val session: File, val identity: String, val manifest: String, val index: Int)
    private data class Fingerprint(val size: Long, val sha256: String)

    private companion object {
        const val DOWNLOAD_TIMEOUT = 240_000L
        const val MAX_FIXTURE_BYTES = 512L * 1024 * 1024
        const val VALIDATION_DETAIL = "正在驗證處理後的影片"
        val CLEANUP_DETAILS = setOf("正在檢查影片中的已知廣告", "正在確認廣告片段", "正在移除已辨識廣告", VALIDATION_DETAIL)
    }
}
