package com.changyow.mediadler.download

import com.changyow.mediadler.core.download.LowStorageException
import com.changyow.mediadler.core.model.*
import com.changyow.mediadler.core.repo.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRunnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun duplicateWakesAndServiceHandoverKeepSingleAttemptAndFifoOrder() = runTest {
        val fixture = fixture(backgroundScope)
        val ids = fixture.queue.submitRequests(listOf(request("1"), request("2"), request("3")))
        fixture.runner.wake("service-a", 1)
        fixture.runner.wake("service-a", 2)
        fixture.runner.wake("service-b", 1)
        runCurrent()
        assertEquals(1, fixture.downloader.calls.size)
        assertEquals("service-b", fixture.runner.state.value.owner)
        assertEquals(1, fixture.runner.state.value.startId)
        fixture.downloader.calls.first().progress(DownloadProgress(DownloadPhase.PROCESSING, detail = "封裝中"))
        assertEquals(DownloadStatus.PROCESSING, fixture.queue.get(ids.first())?.status)

        repeat(3) { index ->
            assertEquals(ids[index], fixture.downloader.calls[index].id)
            fixture.downloader.calls[index].succeed()
            runCurrent()
        }
        assertEquals(3, fixture.downloader.calls.size)
        assertEquals(1, fixture.downloader.maximumActive)
        assertEquals(DownloadRunner.Phase.IDLE, fixture.runner.state.value.phase)
        assertTrue(fixture.queue.tasks.value.all { it.status == DownloadStatus.COMPLETED })
    }

    @Test fun emptyQueueThenNewEnqueueAndWakeDoesNotLoseStartIdOrWork() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runner.wake("service", 1)
        runCurrent()
        assertEquals(DownloadRunner.Phase.IDLE, fixture.runner.state.value.phase)
        assertEquals(1, fixture.runner.state.value.startId)
        val id = fixture.queue.submit(request("new"))
        fixture.runner.wake("service", 2)
        runCurrent()
        assertEquals(id, fixture.downloader.calls.single().id)
        assertEquals(2, fixture.runner.state.value.startId)
        assertEquals(DownloadRunner.Phase.RUNNING, fixture.runner.state.value.phase)
    }

    @Test fun oldOwnerStopCannotCancelNewServiceOwner() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("old", 10)
        runCurrent()
        fixture.runner.wake("new", 1)
        fixture.runner.stop("old")
        runCurrent()
        assertEquals(DownloadRunner.Phase.RUNNING, fixture.runner.state.value.phase)
        assertEquals("new", fixture.runner.state.value.owner)
        assertNull(fixture.queue.pauseReason())
        assertEquals(1, fixture.downloader.active)
        fixture.downloader.calls.single().succeed()
        runCurrent()
        assertEquals(DownloadStatus.COMPLETED, fixture.queue.get(id)?.status)
    }

    @Test fun stoppingWaitsForNativeCleanupBeforeHandoverCanStartAnotherAttempt() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseNative = CompletableDeferred<Unit>()
        val downloader = FakeDownloader { call, index ->
            if (index == 0) {
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseNative.await()
                    }
                }
            } else call.result.await()
        }
        val fixture = fixture(backgroundScope, downloader)
        val id = fixture.queue.submit(request("1"))
        fixture.queue.submit(request("2"))
        fixture.runner.wake("old", 1)
        runCurrent()
        fixture.runner.stop("old")
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertEquals(DownloadRunner.Phase.STOPPING, fixture.runner.state.value.phase)
        assertEquals(1, fixture.queue.activeCount())

        fixture.runner.wake("new", 1, userInitiated = true)
        fixture.runner.stop("old")
        runCurrent()
        assertEquals(DownloadRunner.Phase.STOPPING, fixture.runner.state.value.phase)
        assertEquals(1, downloader.calls.size)
        assertEquals(1, downloader.active)
        releaseNative.complete(Unit)
        runCurrent()
        assertEquals(2, downloader.calls.size)
        assertEquals(id, downloader.calls.last().id)
        assertEquals(1, downloader.maximumActive)
        assertEquals(DownloadRunner.Phase.RUNNING, fixture.runner.state.value.phase)
    }

    @Test fun stopBeforeChildStartsStillAcknowledgesClaimAndCanResume() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("old", 1)
        fixture.runner.stop("old")
        runCurrent()
        assertEquals(DownloadRunner.Phase.PAUSED, fixture.runner.state.value.phase)
        assertEquals(0, fixture.queue.activeCount())
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertTrue(fixture.downloader.calls.isEmpty())
        fixture.runner.wake("new", 1, userInitiated = true)
        runCurrent()
        assertEquals(id, fixture.downloader.calls.single().id)
    }

    @Test fun userCancellationAcknowledgesStopThenContinuesNextQueuedTask() = runTest {
        val fixture = fixture(backgroundScope)
        val first = fixture.queue.submit(request("1"))
        val second = fixture.queue.submit(request("2"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.queue.cancel(first)
        runCurrent()
        assertEquals(DownloadStatus.CANCELED, fixture.queue.get(first)?.status)
        assertEquals(listOf(first), fixture.listener.stopped)
        assertEquals(second, fixture.downloader.calls.last().id)
        assertEquals(1, fixture.downloader.maximumActive)
    }

    @Test fun ordinaryFailureContinuesToNextTaskWithoutAutomaticRetry() = runTest {
        val fixture = fixture(backgroundScope)
        val first = fixture.queue.submit(request("1"))
        val second = fixture.queue.submit(request("2"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.first().fail(IOException("network disconnected"))
        runCurrent()
        assertEquals(DownloadStatus.FAILED, fixture.queue.get(first)?.status)
        assertEquals(listOf(first), fixture.listener.failed)
        assertEquals(second, fixture.downloader.calls.last().id)
        assertNull(fixture.queue.pauseReason())
        assertEquals(2, fixture.downloader.calls.size)
    }

    @Test fun storagePauseSurvivesQueueAndRunnerRecreationAndRequiresExplicitResumeAll() = runTest {
        val fixture = fixture(backgroundScope)
        val first = fixture.queue.submit(request("1"))
        fixture.queue.submit(request("2"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().fail(LowStorageException())
        runCurrent()
        assertEquals(DownloadRunner.Phase.PAUSED, fixture.runner.state.value.phase)
        assertEquals(QueuePauseKind.STORAGE, fixture.queue.pauseReason()?.kind)

        val restarted = DownloadQueue(fixture.store, fixture.root)
        val runner = DownloadRunner(restarted, QueuedRequestResolver(fixture.extractor), fixture.downloader, backgroundScope)
        runner.wake("recreated", 1, userInitiated = true)
        runCurrent()
        assertEquals(DownloadRunner.Phase.PAUSED, runner.state.value.phase)
        assertEquals(1, fixture.downloader.calls.size)
        runner.wake("recreated", 2, userInitiated = true, resumeAll = true)
        runCurrent()
        assertEquals(first, fixture.downloader.calls.last().id)
        assertEquals(2, fixture.downloader.calls.size)
        assertNull(restarted.pauseReason())
    }

    @Test fun foregroundWakeClearsSystemPauseButBackgroundWakeDoesNot() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.queue.submit(request("1"))
        fixture.queue.pause(QueuePause(QueuePauseKind.SYSTEM, "系統暫停"))
        fixture.runner.wake("service", 1)
        runCurrent()
        assertEquals(DownloadRunner.Phase.PAUSED, fixture.runner.state.value.phase)
        assertTrue(fixture.downloader.calls.isEmpty())
        fixture.runner.wake("service", 2, userInitiated = true)
        runCurrent()
        assertEquals(DownloadRunner.Phase.RUNNING, fixture.runner.state.value.phase)
        assertEquals(1, fixture.downloader.calls.size)
        assertNull(fixture.queue.pauseReason())
    }

    @Test fun explicitTaskRetryClearsStoragePauseWithoutResumeAll() = runTest {
        val fixture = fixture(backgroundScope)
        val first = fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().fail(LowStorageException())
        runCurrent()
        assertEquals(DownloadRunner.Phase.PAUSED, fixture.runner.state.value.phase)
        assertTrue(fixture.queue.retry(first))
        fixture.runner.wake("service", 2, userInitiated = true)
        runCurrent()
        assertEquals(DownloadRunner.Phase.RUNNING, fixture.runner.state.value.phase)
        assertEquals(2, fixture.downloader.calls.size)
        assertEquals(first, fixture.downloader.calls.last().id)
    }

    @Test fun retryWhileFailureNotificationIsSuspendedDoesNotRestoreOldStoragePause() = runTest {
        val release = CompletableDeferred<Unit>()
        val listener = RecordingListener().apply { failureGate = release }
        val fixture = fixture(backgroundScope, listener = listener)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().fail(LowStorageException())
        runCurrent()
        assertEquals(DownloadStatus.FAILED, fixture.queue.get(id)?.status)
        assertTrue(fixture.queue.retry(id))
        fixture.runner.wake("service", 2, userInitiated = true)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertNull(fixture.queue.pauseReason())
        assertEquals(2, fixture.downloader.calls.size)
        assertEquals(DownloadRunner.Phase.RUNNING, fixture.runner.state.value.phase)
    }

    @Test fun lifecycleStopAfterRetryDoesNotRestoreAnObsoleteStoragePause() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().fail(LowStorageException())
        runCurrent()
        assertTrue(fixture.queue.retry(id))
        fixture.runner.stop("service")
        runCurrent()
        assertEquals(QueuePauseKind.SYSTEM, fixture.queue.pauseReason()?.kind)
        fixture.runner.wake("service", 2, userInitiated = true)
        runCurrent()
        assertNull(fixture.queue.pauseReason())
        assertEquals(2, fixture.downloader.calls.size)
    }

    @Test fun completedPrivateArtifactIsPublishedBeforeResolvingAnExpiredPage() = runTest {
        val downloader = FakeDownloader().apply {
            recovered = DownloadOutput("content://already-published", "episode.mp4", "video/mp4")
        }
        val fixture = fixture(backgroundScope, downloader)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        fixture.runner.wake("service", 1)
        runCurrent()
        assertTrue(fixture.extractor.urls.isEmpty())
        assertTrue(downloader.calls.isEmpty())
        assertEquals(DownloadStatus.COMPLETED, fixture.queue.get(id)?.status)
        assertEquals("content://already-published", fixture.queue.get(id)?.outputUri)
    }

    @Test fun removingTheLastPausedFailureDoesNotTrapLaterExplicitWork() = runTest {
        val fixture = fixture(backgroundScope)
        val first = fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().fail(LowStorageException())
        runCurrent()
        fixture.queue.remove(first)
        val next = fixture.queue.submit(request("2"))
        fixture.runner.wake("service", 2, userInitiated = true)
        runCurrent()
        assertEquals(next, fixture.downloader.calls.last().id)
        assertEquals(DownloadRunner.Phase.RUNNING, fixture.runner.state.value.phase)
    }

    @Test fun rejectedPageMediaRefreshesOnlySamePageOnceAndDoesNotPreResolveNextEpisode() = runTest {
        val fixture = fixture(backgroundScope)
        val pages = listOf(page(1), page(2))
        val ids = fixture.queue.submitPages(pages, FormatSelection.BestVideo)
        fixture.runner.wake("service", 1)
        runCurrent()
        assertEquals(listOf(pages.first().playbackUrl), fixture.extractor.urls)
        fixture.downloader.calls.first().fail(IOException("HTTP Error 403: Forbidden"))
        runCurrent()
        assertEquals(listOf(pages.first().playbackUrl, pages.first().playbackUrl), fixture.extractor.urls)
        assertEquals(listOf(ids.first(), ids.first()), fixture.downloader.calls.map { it.id })
        assertFalse(fixture.downloader.calls.first().request.item.sourceUrl == fixture.downloader.calls.last().request.item.sourceUrl)
        fixture.downloader.calls.last().fail(IOException("HTTP 410 Gone"))
        runCurrent()
        assertEquals(DownloadStatus.FAILED, fixture.queue.get(ids.first())?.status)
        assertEquals(listOf(pages[0].playbackUrl, pages[0].playbackUrl, pages[1].playbackUrl), fixture.extractor.urls)
        assertEquals(ids.last(), fixture.downloader.calls.last().id)
        assertTrue(fixture.downloader.calls.take(2).all { it.request.item.source.pageUrl == pages.first().playbackUrl })
    }

    @Test fun generalRequestDoesNotUsePageRefreshOn403() = runTest {
        val fixture = fixture(backgroundScope)
        val first = fixture.queue.submit(request("1"))
        val second = fixture.queue.submit(request("2"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.first().fail(IOException("HTTP Error 403"))
        runCurrent()
        assertEquals(DownloadStatus.FAILED, fixture.queue.get(first)?.status)
        assertEquals(listOf(first, second), fixture.downloader.calls.map { it.id })
        assertTrue(fixture.extractor.urls.isEmpty())
    }

    @Test fun completionListenerFailureDoesNotOverwritePublishedOutputOrStopDrain() = runTest {
        val listener = RecordingListener().apply { completionFailure = IOException("history unavailable") }
        val fixture = fixture(backgroundScope, listener = listener)
        val first = fixture.queue.submit(request("1"))
        val second = fixture.queue.submit(request("2"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.first().succeed()
        runCurrent()
        assertEquals(DownloadStatus.COMPLETED, fixture.queue.get(first)?.status)
        assertEquals("content://output/$first", fixture.queue.get(first)?.outputUri)
        assertTrue(listener.failed.isEmpty())
        assertEquals(second, fixture.downloader.calls.last().id)
    }

    @Test fun failedClaimCommitPausesWithoutSpinningAndForegroundWakeCanRecover() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.queue.submit(request("1"))
        fixture.store.failNextWrite = true
        fixture.runner.wake("service", 1)
        runCurrent()
        assertEquals(DownloadRunner.Phase.PAUSED, fixture.runner.state.value.phase)
        assertTrue(fixture.downloader.calls.isEmpty())
        val writes = fixture.store.writes
        runCurrent()
        assertEquals(writes, fixture.store.writes)
        fixture.runner.wake("service", 2, userInitiated = true)
        runCurrent()
        assertEquals(1, fixture.downloader.calls.size)
    }

    @Test fun failedStopAcknowledgementBlocksNextTaskUntilExplicitResumeRepairsIt() = runTest {
        val fixture = fixture(backgroundScope)
        val first = fixture.queue.submit(request("1"))
        val second = fixture.queue.submit(request("2"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.queue.cancel(first)
        fixture.store.failNextWrite = true
        runCurrent()
        assertEquals(DownloadRunner.Phase.PAUSED, fixture.runner.state.value.phase)
        assertEquals(1, fixture.downloader.calls.size)
        assertEquals(1, fixture.queue.activeCount())
        fixture.runner.wake("service", 2)
        runCurrent()
        assertEquals(1, fixture.downloader.calls.size)
        fixture.runner.wake("service", 3, userInitiated = true)
        runCurrent()
        assertEquals(DownloadStatus.CANCELED, fixture.queue.get(first)?.status)
        assertEquals(second, fixture.downloader.calls.last().id)
        assertEquals(listOf(first), fixture.listener.stopped)
    }

    private fun fixture(
        scope: CoroutineScope,
        downloader: FakeDownloader = FakeDownloader(),
        listener: RecordingListener = RecordingListener(),
    ): Fixture {
        val root = temporary.newFolder()
        val store = MemoryStore()
        val queue = DownloadQueue(store, root)
        val extractor = RecordingExtractor()
        return Fixture(root, store, queue, extractor, downloader, listener,
            DownloadRunner(queue, QueuedRequestResolver(extractor), downloader, scope, listener))
    }

    private data class Fixture(
        val root: File, val store: MemoryStore, val queue: DownloadQueue,
        val extractor: RecordingExtractor, val downloader: FakeDownloader,
        val listener: RecordingListener, val runner: DownloadRunner,
    )

    private class MemoryStore : DownloadQueueStore {
        private var records = emptyList<QueuedDownload>()
        var failNextWrite = false
        var writes = 0
        override fun read() = records.toList()
        override fun transact(change: (List<QueuedDownload>) -> List<QueuedDownload>): List<QueuedDownload> {
            writes++
            val next = change(read()).toList()
            if (failNextWrite) { failNextWrite = false; throw IOException("store unavailable") }
            records = next
            return read()
        }
    }

    private class RecordingListener : DownloadRunner.Listener {
        val stopped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var completionFailure: Exception? = null
        var failureGate: CompletableDeferred<Unit>? = null
        override fun onStopped(taskId: String, task: DownloadTask?) { stopped += taskId }
        override suspend fun onFailed(task: DownloadTask, error: Throwable) { failed += task.id; failureGate?.await() }
        override suspend fun onCompleted(task: DownloadTask, output: DownloadOutput) { completionFailure?.let { throw it } }
    }

    private class RecordingExtractor : MediaExtractor {
        val urls = mutableListOf<String>()
        override suspend fun extract(url: String): Result<List<MediaItem>> {
            urls += url
            return Result.success(listOf(item("https://cdn.example/stream-${urls.size}.m3u8",
                SourceContext(url, url, SourceKind.EIGHT_MAPLE))))
        }
    }

    private class FakeDownloader(
        val action: suspend (Call, Int) -> Result<DownloadOutput> = { call, _ -> call.result.await() },
    ) : Downloader {
        val calls = mutableListOf<Call>()
        var active = 0
        var maximumActive = 0
        var recovered: DownloadOutput? = null
        override suspend fun recover(processId: String, onProgress: (DownloadProgress) -> Unit): Result<DownloadOutput?> =
            Result.success(recovered)
        override suspend fun download(request: DownloadRequest, processId: String, onProgress: (DownloadProgress) -> Unit): Result<DownloadOutput> {
            val call = Call(request, processId, onProgress)
            val index = calls.size
            calls += call
            active++
            maximumActive = maxOf(maximumActive, active)
            return try { action(call, index) } finally { active-- }
        }
    }

    private class Call(val request: DownloadRequest, val id: String, val progress: (DownloadProgress) -> Unit) {
        val result = CompletableDeferred<Result<DownloadOutput>>()
        fun succeed() { result.complete(Result.success(DownloadOutput("content://output/$id", "$id.mp4", "video/mp4"))) }
        fun fail(error: Throwable) { result.complete(Result.failure(error)) }
    }

    private fun request(id: String) = DownloadRequest(item("https://media.example/$id"), FormatSelection.BestVideo)
    private fun page(number: Int) = EpisodePage("https://play.8maple.st/play/OD4C-2-$number.html", SourceKind.EIGHT_MAPLE)

    private companion object {
        fun item(url: String, source: SourceContext = SourceContext(url, url)) = MediaItem(
            sourceUrl = url, playlistIndex = null, id = url, title = "Episode", thumbnailUrl = null,
            durationSeconds = null, isImage = false, formats = emptyList(), source = source,
        )
    }
}
