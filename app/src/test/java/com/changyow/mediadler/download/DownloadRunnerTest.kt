package com.changyow.mediadler.download

import com.changyow.mediadler.core.download.LowStorageException
import com.changyow.mediadler.core.model.*
import com.changyow.mediadler.core.repo.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
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
        fixture.downloader.calls.first().fail(IOException("unsupported media format"))
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

    @Test fun networkFailureParksTheFifoAndOnlyBackgroundOwnershipResumesIt() = runTest {
        val network = MutableStateFlow(true)
        val fixture = fixture(backgroundScope, networkAvailable = network)
        val ids = fixture.queue.submitRequests(listOf(request("1"), request("2")))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().fail(IOException("Connection reset"))
        runCurrent()
        assertEquals(DownloadRunner.Phase.PAUSED, fixture.runner.state.value.phase)
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
        assertTrue(fixture.queue.tasks.value.all { it.status == DownloadStatus.QUEUED })
        assertTrue(fixture.listener.failed.isEmpty())
        network.value = false
        advanceTimeBy(12 * 60 * 60_000L)
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
        assertEquals(1, fixture.downloader.calls.size)
        network.value = true
        runCurrent()
        assertEquals(1, fixture.downloader.calls.size) // A connectivity callback never starts an unowned attempt.
        val acquired = async { fixture.runner.acquireBackground("worker") }
        runCurrent()
        assertTrue(acquired.await())
        assertEquals(ids.first(), fixture.downloader.calls.last().id)
        assertEquals("worker", fixture.runner.state.value.owner)
        assertNull(fixture.queue.pauseReason())
    }

    @Test fun offlineWakeNeverClaimsExtractsOrDownloadsAndForegroundWakeRechecksConnectivity() = runTest {
        val network = MutableStateFlow(false)
        val fixture = fixture(backgroundScope, networkAvailable = network)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        fixture.runner.wake("service", 1, userInitiated = true)
        runCurrent()
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
        assertTrue(fixture.extractor.urls.isEmpty())
        assertTrue(fixture.downloader.calls.isEmpty())
        val acquired = async { fixture.runner.acquireBackground("worker") }
        runCurrent()
        assertFalse(acquired.await())
        fixture.runner.wake("service", 2, userInitiated = true)
        runCurrent()
        assertTrue(fixture.extractor.urls.isEmpty())
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
    }

    @Test fun extractionNetworkFailureParksBeforeDownloadingAndRecreatedRunnerResumesSameTask() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        fixture.extractor.failure = IOException("Unable to resolve host cdn.example")
        fixture.runner.wake("service", 1)
        runCurrent()
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
        assertTrue(fixture.downloader.calls.isEmpty())
        fixture.extractor.failure = null
        val reopened = DownloadQueue(fixture.store, fixture.root)
        val runner = DownloadRunner(reopened, QueuedRequestResolver(fixture.extractor), fixture.downloader, backgroundScope)
        val acquired = async { runner.acquireBackground("worker") }
        runCurrent()
        assertTrue(acquired.await())
        assertEquals(id, fixture.downloader.calls.single().id)
        assertNull(reopened.pauseReason())
    }

    @Test fun backgroundOwnershipCannotClearSystemStorageOrPersistenceGateOrStealForeground() = runTest {
        for (kind in listOf(QueuePauseKind.SYSTEM, QueuePauseKind.STORAGE)) {
            val fixture = fixture(backgroundScope)
            fixture.queue.submit(request("1"))
            fixture.queue.pause(QueuePause(kind, "paused"))
            val acquired = async { fixture.runner.acquireBackground("worker") }
            runCurrent()
            assertFalse(acquired.await())
            assertEquals(kind, fixture.queue.pauseReason()?.kind)
            assertTrue(fixture.downloader.calls.isEmpty())
        }
        val fixture = fixture(backgroundScope)
        fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        val acquired = async { fixture.runner.acquireBackground("worker") }
        runCurrent()
        assertFalse(acquired.await())
        assertEquals("service", fixture.runner.state.value.owner)
        assertEquals(1, fixture.downloader.calls.size)
        fixture.store.failNextWrite = true
        fixture.runner.stop("service")
        runCurrent()
        val blocked = async { fixture.runner.acquireBackground("worker") }
        runCurrent()
        assertFalse(blocked.await())
    }

    @Test fun canceledBackgroundAcquireCannotStartADownload() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.queue.submit(request("1"))
        val acquired = launch(start = CoroutineStart.UNDISPATCHED) { fixture.runner.acquireBackground("worker") }
        acquired.cancel()
        runCurrent()
        assertTrue(fixture.downloader.calls.isEmpty())
        assertNull(fixture.runner.state.value.owner)
    }

    @Test fun acknowledgedStopBeforeChildStartsReleasesItsClaimWithoutNativeWork() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("worker", 1)
        val stopped = async(start = CoroutineStart.UNDISPATCHED) { fixture.runner.stopAndAwait("worker") }
        runCurrent()
        stopped.await()
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertEquals(0, fixture.queue.activeCount())
        assertTrue(fixture.downloader.calls.isEmpty())
        assertEquals(DownloadRunner.Phase.PAUSED, fixture.runner.state.value.phase)
    }

    @Test fun stopAndAwaitWaitsForNativeCleanupAndStaleWorkerCannotStopNewService() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseNative = CompletableDeferred<Unit>()
        val downloader = FakeDownloader { _, _ ->
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseNative.await()
                }
            }
        }
        val fixture = fixture(backgroundScope, downloader)
        val id = fixture.queue.submit(request("1"))
        val acquired = async { fixture.runner.acquireBackground("worker") }
        runCurrent()
        assertTrue(acquired.await())
        val stopped = async { fixture.runner.stopAndAwait("worker") }
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertFalse(stopped.isCompleted)
        val overlapping = async { fixture.runner.acquireBackground("other-worker") }
        runCurrent()
        assertFalse(overlapping.await())
        assertEquals(1, downloader.calls.size)
        releaseNative.complete(Unit)
        runCurrent()
        stopped.await()
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        fixture.runner.wake("service", 1, userInitiated = true)
        runCurrent()
        val staleStop = async { fixture.runner.stopAndAwait("worker") }
        runCurrent()
        staleStop.await()
        assertEquals("service", fixture.runner.state.value.owner)
        assertEquals(DownloadRunner.Phase.RUNNING, fixture.runner.state.value.phase)
        assertNull(fixture.queue.pauseReason())
    }

    @Test fun completedWorkerReleaseDoesNotPauseAnEmptyQueue() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.queue.submit(request("1"))
        val acquired = async { fixture.runner.acquireBackground("worker") }
        runCurrent()
        assertTrue(acquired.await())
        fixture.downloader.calls.single().succeed()
        runCurrent()
        assertEquals(DownloadRunner.Phase.IDLE, fixture.runner.state.value.phase)
        val stopped = async {
            fixture.runner.stopAndAwait("worker", QueuePause(QueuePauseKind.NETWORK, "waiting"))
        }
        runCurrent()
        stopped.await()
        assertEquals(DownloadRunner.Phase.IDLE, fixture.runner.state.value.phase)
        assertNull(fixture.runner.state.value.pause)
        assertNull(fixture.queue.pauseReason())
    }

    @Test fun successiveBackgroundWindowsKeepTheSamePageResourceAndBytesUntilCompletion() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        var cleanedUp = 0
        lateinit var partial: File
        val downloader = FakeDownloader { call, index ->
            if (index == 0) {
                partial.parentFile!!.mkdirs()
                partial.writeText("first fragments")
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        delay(100)
                        cleanedUp++
                    }
                }
            } else call.result.await()
        }
        val fixture = fixture(backgroundScope, downloader)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        partial = File(fixture.queue.workDirectory(id), "video.part")
        val firstWindow = async { fixture.runner.runBackgroundWindow("worker-1", 1_000) }
        runCurrent()
        val originalRequest = downloader.calls.single().request
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertFalse(firstWindow.isCompleted)
        assertEquals(DownloadRunner.Phase.STOPPING, fixture.runner.state.value.phase)
        advanceTimeBy(100)
        runCurrent()
        firstWindow.await()
        assertEquals(1, cleanedUp)
        assertEquals(0, downloader.active)
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertEquals("first fragments", partial.readText())

        val secondWindow = async { fixture.runner.runBackgroundWindow("worker-2", 1_000) }
        runCurrent()
        assertEquals(1, fixture.extractor.urls.size)
        assertEquals(listOf(id, id), downloader.calls.map { it.id })
        assertEquals(originalRequest, downloader.calls.last().request)
        assertEquals("first fragments", partial.readText())
        downloader.calls.last().succeed()
        runCurrent()
        secondWindow.await()
        assertEquals(DownloadStatus.COMPLETED, fixture.queue.get(id)?.status)
        assertEquals(0, downloader.active)
        assertEquals(1, downloader.maximumActive)
        assertNull(fixture.queue.pauseReason())
    }

    @Test fun externalBackgroundWindowCancellationPropagatesOnlyAfterNativeCleanup() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val downloader = FakeDownloader { _, _ ->
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }
        val fixture = fixture(backgroundScope, downloader)
        val id = fixture.queue.submit(request("1"))
        val window = async { fixture.runner.runBackgroundWindow("worker", 10_000) }
        runCurrent()
        window.cancel(CancellationException("worker constraints changed"))
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertFalse(window.isCompleted)
        releaseCleanup.complete(Unit)
        runCurrent()
        var cancellation: CancellationException? = null
        try { window.await() } catch (error: CancellationException) { cancellation = error }
        assertEquals("worker constraints changed", cancellation?.message)
        assertEquals(0, downloader.active)
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
    }

    @Test fun processingAtBackgroundWindowExpiryRequiresForegroundResume() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submit(request("1"))
        val window = async { fixture.runner.runBackgroundWindow("worker", 1_000) }
        runCurrent()
        fixture.downloader.calls.single().progress(DownloadProgress(DownloadPhase.PROCESSING, detail = "encoding"))
        advanceTimeBy(1_000)
        runCurrent()
        window.await()
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertEquals(QueuePauseKind.SYSTEM, fixture.queue.pauseReason()?.kind)
        val retryWindow = async { fixture.runner.runBackgroundWindow("worker-2", 1_000) }
        runCurrent()
        retryWindow.await()
        assertEquals(1, fixture.downloader.calls.size)
        assertEquals(QueuePauseKind.SYSTEM, fixture.queue.pauseReason()?.kind)
        fixture.runner.wake("service", 1, userInitiated = true)
        runCurrent()
        assertEquals(id, fixture.downloader.calls.last().id)
        assertEquals(2, fixture.downloader.calls.size)
    }

    @Test fun expiredCachedBackgroundUrlRefreshesOnlyTheSamePageOnce() = runTest {
        val fixture = fixture(backgroundScope)
        val page = page(1)
        val id = fixture.queue.submitPages(listOf(page), FormatSelection.BestVideo).single()
        fixture.runner.wake("service", 1)
        runCurrent()
        val originalRequest = fixture.downloader.calls.single().request
        fixture.downloader.calls.single().fail(IOException("Connection reset"))
        runCurrent()
        val window = async { fixture.runner.runBackgroundWindow("worker", 1_000) }
        runCurrent()
        assertEquals(originalRequest, fixture.downloader.calls.last().request)
        assertEquals(listOf(page.playbackUrl), fixture.extractor.urls)
        fixture.downloader.calls.last().fail(IOException("HTTP Error 403"))
        runCurrent()
        assertEquals(listOf(page.playbackUrl, page.playbackUrl), fixture.extractor.urls)
        assertEquals(listOf(id, id, id), fixture.downloader.calls.map { it.id })
        assertFalse(originalRequest.item.sourceUrl == fixture.downloader.calls.last().request.item.sourceUrl)
        fixture.downloader.calls.last().fail(IOException("HTTP Error 410"))
        runCurrent()
        window.await()
        assertEquals(DownloadStatus.FAILED, fixture.queue.get(id)?.status)
        assertEquals(2, fixture.extractor.urls.size)
    }

    @Test fun manualRetryAfterBackgroundContinuationStillResolvesThePlaybackPageAgain() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        val window = async { fixture.runner.runBackgroundWindow("worker", 1_000) }
        runCurrent()
        val originalRequest = fixture.downloader.calls.single().request
        fixture.downloader.calls.single().fail(IOException("unsupported media format"))
        runCurrent()
        window.await()
        assertTrue(fixture.queue.retry(id))
        fixture.runner.wake("service", 1, userInitiated = true)
        runCurrent()
        assertEquals(2, fixture.extractor.urls.size)
        assertFalse(originalRequest.item.sourceUrl == fixture.downloader.calls.last().request.item.sourceUrl)
    }

    private fun fixture(
        scope: CoroutineScope,
        downloader: FakeDownloader = FakeDownloader(),
        listener: RecordingListener = RecordingListener(),
        networkAvailable: StateFlow<Boolean> = MutableStateFlow(true),
    ): Fixture {
        val root = temporary.newFolder()
        val store = MemoryStore()
        val queue = DownloadQueue(store, root)
        val extractor = RecordingExtractor()
        return Fixture(root, store, queue, extractor, downloader, listener,
            DownloadRunner(queue, QueuedRequestResolver(extractor), downloader, scope, listener, networkAvailable))
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
        var failure: Throwable? = null
        override suspend fun extract(url: String): Result<List<MediaItem>> {
            urls += url
            failure?.let { return Result.failure(it) }
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
