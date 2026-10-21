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

    @Test fun skipAnalysisWaitsForNativeCleanupThenReusesSameJobWithFullContentIntent() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val downloader = FakeDownloader { call, index ->
            if (index == 0) {
                call.progress(DownloadProgress(DownloadPhase.AD_ANALYSIS, detail = "OCR", canResume = true))
                try { awaitCancellation() } finally { withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                } }
            } else call.result.await()
        }
        val fixture = fixture(backgroundScope, downloader)
        val ids = fixture.queue.submitPages(listOf(page(1), page(2)), FormatSelection.BestVideo, AdRemovalPolicy.WEIGHTED)
        fixture.runner.wake("service", 1)
        runCurrent()
        val skipped = async { fixture.runner.skipAdAnalysis(ids.first()) }
        runCurrent()
        assertTrue(skipped.await())
        val duplicate = async { fixture.runner.skipAdAnalysis(ids.first()) }
        runCurrent()
        assertTrue(duplicate.await())
        assertTrue(cleanupStarted.isCompleted)
        assertEquals(1, downloader.calls.size)
        assertEquals(1, downloader.active)
        assertEquals(DownloadRunner.Phase.STOPPING, fixture.runner.state.value.phase)
        assertTrue(fixture.queue.get(ids.first())!!.skipAdAnalysis)
        // A stale filtered artifact from the preceding attempt is unavailable to the new recovery overload.
        downloader.recovered = DownloadOutput("content://old-filtered", "old.mp4", "video/mp4")
        releaseCleanup.complete(Unit)
        runCurrent()
        assertEquals(2, downloader.calls.size)
        assertEquals(ids.first(), downloader.calls.last().id)
        assertTrue(downloader.calls.last().request.skipAdAnalysis)
        assertEquals(AdRemovalPolicy.WEIGHTED, downloader.calls.last().request.adRemovalPolicy)
        assertEquals(listOf(ids.first()), downloader.recoveryCalls)
        assertEquals(1, fixture.extractor.urls.size)
        assertEquals(1, downloader.maximumActive)
        assertNull(fixture.queue.pauseReason())
        assertTrue(fixture.listener.failed.isEmpty())
        downloader.recovered = null
        downloader.calls.last().succeed()
        runCurrent()
        assertEquals(ids.last(), downloader.calls.last().id)
        assertFalse(downloader.calls.last().request.skipAdAnalysis)
    }

    @Test fun transferBoundaryRejectsSkipAndRetainsOrdinaryPause() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().progress(DownloadProgress(DownloadPhase.AD_ANALYSIS))
        fixture.downloader.calls.single().progress(DownloadProgress(DownloadPhase.DOWNLOADING))
        val skipped = async { fixture.runner.skipAdAnalysis(id) }
        runCurrent()
        assertFalse(skipped.await())
        assertFalse(fixture.queue.get(id)!!.skipAdAnalysis)
        val paused = async { fixture.runner.pauseTask(id) }
        runCurrent()
        assertTrue(paused.await())
        assertEquals(DownloadStatus.PAUSED, fixture.queue.get(id)?.status)
        assertEquals(1, fixture.downloader.maximumActive)
    }

    @Test fun lifecycleStopWinsOverPendingSkipUntilExplicitOwnerResume() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val downloader = FakeDownloader { call, index ->
            if (index == 0) {
                call.progress(DownloadProgress(DownloadPhase.AD_ANALYSIS))
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            } else call.result.await()
        }
        val fixture = fixture(backgroundScope, downloader)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        val skipped = async { fixture.runner.skipAdAnalysis(id) }
        runCurrent()
        assertTrue(skipped.await())
        fixture.runner.stop("service")
        runCurrent()
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(1, downloader.calls.size)
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertTrue(fixture.queue.get(id)!!.skipAdAnalysis)
        assertNull(fixture.queue.get(id)?.progressPhase)
        assertEquals(QueuePauseKind.SYSTEM, fixture.queue.pauseReason()?.kind)
        fixture.runner.wake("service", 2, userInitiated = true)
        runCurrent()
        assertEquals(2, downloader.calls.size)
        assertTrue(downloader.calls.last().request.skipAdAnalysis)
        assertEquals(1, downloader.maximumActive)
    }

    @Test fun skipIntentSurvivesSourceRefreshManualRetryAndRunnerRecreationWithFrozenPolicy() = runTest {
        val downloader = FakeDownloader { call, index ->
            if (index == 0) call.progress(DownloadProgress(DownloadPhase.AD_ANALYSIS))
            call.result.await()
        }
        val fixture = fixture(backgroundScope, downloader)
        val page = page(1)
        val id = fixture.queue.submitPages(listOf(page), FormatSelection.BestVideo, AdRemovalPolicy.WEIGHTED).single()
        fixture.runner.wake("service", 1)
        runCurrent()
        val skip = async { fixture.runner.skipAdAnalysis(id) }
        runCurrent()
        assertTrue(skip.await())
        assertEquals(2, downloader.calls.size)
        assertEquals(1, fixture.extractor.urls.size)
        downloader.calls.last().fail(IOException("HTTP Error 403"))
        runCurrent()
        assertEquals(listOf(page.playbackUrl, page.playbackUrl), fixture.extractor.urls)
        assertEquals(3, downloader.calls.size)
        assertTrue(downloader.calls.drop(1).all { it.request.skipAdAnalysis })
        assertTrue(downloader.calls.all { it.id == id && it.request.adRemovalPolicy == AdRemovalPolicy.WEIGHTED })
        downloader.calls.last().fail(IOException("HTTP Error 410"))
        runCurrent()
        assertEquals(DownloadStatus.FAILED, fixture.queue.get(id)?.status)
        val reopened = DownloadQueue(fixture.store, fixture.root)
        assertTrue(reopened.retry(id))
        val runner = DownloadRunner(reopened, QueuedRequestResolver(fixture.extractor), downloader, backgroundScope)
        runner.wake("new-service", 1, userInitiated = true)
        runCurrent()
        assertEquals(4, downloader.calls.size)
        assertEquals(3, fixture.extractor.urls.size)
        assertEquals(id, downloader.calls.last().id)
        assertTrue(downloader.calls.last().request.skipAdAnalysis)
        assertEquals(AdRemovalPolicy.WEIGHTED, downloader.calls.last().request.adRemovalPolicy)
        assertEquals(1, downloader.maximumActive)
    }

    @Test fun failedSkipPersistenceStopsCurrentAttemptWithoutSettingFullContentIntent() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().progress(DownloadProgress(DownloadPhase.AD_ANALYSIS))
        fixture.store.failNextWrite = true
        val skipped = async { fixture.runner.skipAdAnalysis(id) }
        runCurrent()
        assertFalse(skipped.await())
        assertFalse(fixture.queue.get(id)!!.skipAdAnalysis)
        assertEquals(QueuePauseKind.SYSTEM, fixture.queue.pauseReason()?.kind)
        assertEquals(DownloadRunner.Phase.PAUSED, fixture.runner.state.value.phase)
        assertEquals(1, fixture.downloader.calls.size)
        assertEquals(0, fixture.downloader.active)
    }

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

    @Test fun publicationInterruptedByProcessDeathCanFinishWhenReopenedOffline() = runTest {
        val network = MutableStateFlow(false)
        val downloader = FakeDownloader().apply {
            recovered = DownloadOutput("content://already-published", "episode.mp4", "video/mp4")
        }
        val fixture = fixture(backgroundScope, downloader, networkAvailable = network)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        val claim = fixture.queue.claimNext()!!
        fixture.queue.processing(claim)
        val reopened = DownloadQueue(fixture.store, fixture.root)
        val runner = DownloadRunner(reopened, QueuedRequestResolver(fixture.extractor), downloader,
            backgroundScope, networkAvailable = network)

        runner.wake("reopened-service", 1, userInitiated = true)
        runCurrent()

        assertEquals(listOf(id), downloader.recoveryCalls)
        assertEquals(DownloadStatus.COMPLETED, reopened.get(id)?.status)
        assertEquals("content://already-published", reopened.get(id)?.outputUri)
        assertTrue(fixture.extractor.urls.isEmpty())
        assertTrue(downloader.calls.isEmpty())
        assertNull(reopened.pauseReason())
        assertEquals(DownloadRunner.Phase.IDLE, runner.state.value.phase)
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

    @Test fun sourceConnectionFailureWhileOnlineFailsOnlyThatItemAndContinuesTheFifo() = runTest {
        val fixture = fixture(backgroundScope, networkAvailable = MutableStateFlow(true))
        val ids = fixture.queue.submitRequests(listOf(request("unavailable-source"), request("working-source")))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().fail(IOException("Connection refused by source.example"))
        advanceTimeBy(500)
        runCurrent()

        assertEquals(DownloadStatus.FAILED, fixture.queue.get(ids.first())?.status)
        assertNull(fixture.queue.pauseReason())
        assertEquals(listOf(ids.first()), fixture.listener.failed)
        assertEquals(ids.last(), fixture.downloader.calls.last().id)
        assertEquals(DownloadRunner.Phase.RUNNING, fixture.runner.state.value.phase)
        assertTrue(fixture.queue.get(ids.first())!!.errorMessage!!.contains("此來源目前無法載入"))
        assertTrue(fixture.queue.get(ids.first())!!.errorMessage!!.contains("Connection refused"))
    }

    @Test fun delayedOfflineCallbackDuringFailureGraceRetainsTheTaskAndCheckpoint() = runTest {
        val network = MutableStateFlow(true)
        val fixture = fixture(backgroundScope, networkAvailable = network)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        val partial = File(fixture.queue.workDirectory(id), "media.part").apply {
            parentFile!!.mkdirs()
            writeText("retained fragments")
        }
        fixture.downloader.calls.single().fail(IOException("Connection reset"))
        runCurrent()
        advanceTimeBy(200)
        network.value = false
        runCurrent()

        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
        assertTrue(fixture.listener.failed.isEmpty())
        assertEquals("retained fragments", partial.readText())
    }

    @Test fun cancellationDuringOnlineFailureGraceDoesNotBecomeASourceFailure() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().fail(IOException("Connection refused"))
        runCurrent()
        fixture.queue.cancel(id)
        advanceTimeBy(500)
        runCurrent()

        assertEquals(DownloadStatus.CANCELED, fixture.queue.get(id)?.status)
        assertTrue(fixture.listener.failed.isEmpty())
        assertNull(fixture.queue.pauseReason())
    }

    @Test fun onlineExtractionFailuresFailEachPageWithoutGlobalPause() = runTest {
        val fixture = fixture(backgroundScope)
        val ids = fixture.queue.submitPages(listOf(page(1), page(2)), FormatSelection.BestVideo)
        fixture.extractor.failure = IOException("Unable to resolve host unavailable.example")
        fixture.runner.wake("service", 1)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertEquals(DownloadStatus.FAILED, fixture.queue.get(ids.first())?.status)
        assertEquals(DownloadStatus.FAILED, fixture.queue.get(ids.last())?.status)
        assertNull(fixture.queue.pauseReason())
        assertEquals(2, fixture.extractor.urls.size)
    }

    @Test fun manualPauseWaitsForNativeCleanupAndResumeReusesTheSameCheckpoint() = runTest {
        lateinit var partial: File
        val cleanup = CompletableDeferred<Unit>()
        val downloader = FakeDownloader { call, index ->
            if (index == 0) {
                partial.parentFile!!.mkdirs()
                partial.writeText("first HLS fragments")
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleanup.complete(Unit); delay(100) }
                }
            } else call.result.await()
        }
        val fixture = fixture(backgroundScope, downloader)
        val ids = fixture.queue.submitPages(listOf(page(1), page(2)), FormatSelection.BestVideo)
        partial = File(fixture.queue.workDirectory(ids.first()), "media.part")
        fixture.runner.wake("service", 1)
        runCurrent()
        val original = downloader.calls.single().request
        val pause = async { fixture.runner.pauseTask(ids.first()) }
        runCurrent()
        assertTrue(pause.await())
        assertTrue(cleanup.isCompleted)
        assertEquals(DownloadRunner.Phase.STOPPING, fixture.runner.state.value.phase)
        assertEquals(1, downloader.calls.size)
        assertEquals(1, fixture.queue.activeCount())

        advanceTimeBy(100)
        runCurrent()
        assertEquals(DownloadStatus.PAUSED, fixture.queue.get(ids.first())?.status)
        assertEquals(ids.last(), downloader.calls.last().id)
        assertEquals("first HLS fragments", partial.readText())
        val resume = async { fixture.runner.resumeTask(ids.first()) }
        runCurrent()
        assertTrue(resume.await())
        downloader.calls.last().succeed()
        runCurrent()
        assertEquals(listOf(ids.first(), ids.last(), ids.first()), downloader.calls.map { it.id })
        assertEquals(original, downloader.calls.last().request)
        assertEquals(listOf(page(1).playbackUrl, page(2).playbackUrl), fixture.extractor.urls)
        assertEquals("first HLS fragments", partial.readText())
        assertEquals(1, downloader.maximumActive)
        downloader.calls.last().succeed()
        runCurrent()
        assertTrue(fixture.queue.tasks.value.all { it.status == DownloadStatus.COMPLETED })
    }

    @Test fun downloadNowPreemptsAfterNativeCleanupAndReturnsTheOldItemToSecondPlace() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val downloader = FakeDownloader { call, index ->
            if (index == 0) try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleanup.complete(Unit); delay(100) }
            } else call.result.await()
        }
        val fixture = fixture(backgroundScope, downloader)
        val ids = fixture.queue.submitPages(listOf(page(1), page(2), page(3)), FormatSelection.BestVideo)
        fixture.runner.wake("service", 1)
        runCurrent()
        val original = downloader.calls.single().request
        val now = async { fixture.runner.downloadNow(ids[2]) }
        runCurrent()
        assertEquals(DownloadNowResult.PREEMPTING, now.await())
        assertTrue(cleanup.isCompleted)
        assertEquals(1, downloader.calls.size)
        assertEquals(listOf(ids[2], ids[0], ids[1]), fixture.queue.tasks.value.sortedBy { it.queuePosition }.map { it.id })
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ids[2], downloader.calls.last().id)
        downloader.calls.last().succeed()
        runCurrent()
        assertEquals(ids[0], downloader.calls.last().id)
        assertEquals(original, downloader.calls.last().request)
        downloader.calls.last().succeed()
        runCurrent()
        assertEquals(ids[1], downloader.calls.last().id)
        assertEquals(listOf(ids[0], ids[2], ids[0], ids[1]), downloader.calls.map { it.id })
        assertEquals(1, downloader.maximumActive)
    }

    @Test fun repeatedDownloadNowUsesTheLatestChoiceWithoutStartingOverlappingAttempts() = runTest {
        var cleanups = 0
        val downloader = FakeDownloader { call, index ->
            if (index == 0) try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleanups++; delay(100) }
            } else call.result.await()
        }
        val fixture = fixture(backgroundScope, downloader)
        val ids = fixture.queue.submitRequests((1..4).map { request(it.toString()) })
        fixture.runner.wake("service", 1)
        runCurrent()
        val first = async { fixture.runner.downloadNow(ids[2]) }
        runCurrent()
        assertEquals(DownloadNowResult.PREEMPTING, first.await())
        val second = async { fixture.runner.downloadNow(ids[3]) }
        runCurrent()
        assertEquals(DownloadNowResult.PREEMPTING, second.await())
        assertEquals(1, downloader.calls.size)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ids[3], downloader.calls.last().id)
        assertEquals(1, cleanups)
        assertEquals(1, downloader.maximumActive)
        assertEquals(listOf(ids[3], ids[0], ids[2], ids[1]),
            fixture.queue.tasks.value.sortedBy { it.queuePosition }.map { it.id })
    }

    @Test fun pauseCommittedBeforeProcessingPreventsThePipelineFromPublishing() = runTest {
        lateinit var fixture: Fixture
        var reachedPublication = false
        val downloader = FakeDownloader { call, _ ->
            assertTrue(fixture.queue.pauseTask(call.id))
            call.progress(DownloadProgress(DownloadPhase.PROCESSING))
            reachedPublication = true
            call.result.await()
        }
        fixture = fixture(backgroundScope, downloader)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("service", 1)
        runCurrent()
        assertFalse(reachedPublication)
        assertEquals(DownloadStatus.PAUSED, fixture.queue.get(id)?.status)
        assertTrue(fixture.listener.failed.isEmpty())
    }

    @Test fun processingFinishesBeforeThePrioritizedItemAndCannotBeManuallyPaused() = runTest {
        val fixture = fixture(backgroundScope)
        val ids = fixture.queue.submitRequests((1..3).map { request(it.toString()) })
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.downloader.calls.single().progress(DownloadProgress(DownloadPhase.PROCESSING))
        val pause = async { fixture.runner.pauseTask(ids.first()) }
        val now = async { fixture.runner.downloadNow(ids[2]) }
        runCurrent()
        assertFalse(pause.await())
        assertEquals(DownloadNowResult.AFTER_PROCESSING, now.await())
        assertEquals(1, fixture.downloader.calls.size)
        assertNull(fixture.queue.get(ids.first())?.interruption)
        fixture.downloader.calls.single().succeed()
        runCurrent()
        assertEquals(ids[2], fixture.downloader.calls.last().id)
        assertEquals(DownloadStatus.COMPLETED, fixture.queue.get(ids.first())?.status)
    }

    @Test fun resumeOfAnIdlePausedItemWaitsForANewOwnerWake() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submit(request("1"))
        fixture.runner.wake("old-service", 1)
        runCurrent()
        val pause = async { fixture.runner.pauseTask(id) }
        runCurrent()
        assertTrue(pause.await())
        assertEquals(DownloadStatus.PAUSED, fixture.queue.get(id)?.status)
        val resume = async { fixture.runner.resumeTask(id) }
        runCurrent()
        assertTrue(resume.await())
        assertEquals(1, fixture.downloader.calls.size)
        fixture.runner.wake("new-service", 1, userInitiated = true)
        runCurrent()
        assertEquals(2, fixture.downloader.calls.size)
        assertEquals("new-service", fixture.runner.state.value.owner)
    }

    @Test fun failedPriorityTransactionStopsTheClaimGateWithoutStartingTheNewItem() = runTest {
        val fixture = fixture(backgroundScope)
        val ids = fixture.queue.submitRequests(listOf(request("1"), request("2")))
        fixture.runner.wake("service", 1)
        runCurrent()
        fixture.store.failNextWrite = true
        val now = async { fixture.runner.downloadNow(ids.last()) }
        runCurrent()
        assertEquals(DownloadNowResult.REJECTED, now.await())
        assertEquals(QueuePauseKind.SYSTEM, fixture.queue.pauseReason()?.kind)
        assertEquals(1, fixture.downloader.calls.size)
        assertEquals(0, fixture.downloader.active)
        assertEquals(DownloadRunner.Phase.PAUSED, fixture.runner.state.value.phase)
    }

    @Test fun networkFailureParksTheFifoAndOnlyBackgroundOwnershipResumesIt() = runTest {
        val network = MutableStateFlow(true)
        val fixture = fixture(backgroundScope, networkAvailable = network)
        val ids = fixture.queue.submitRequests(listOf(request("1"), request("2")))
        fixture.runner.wake("service", 1)
        runCurrent()
        network.value = false
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

    @Test fun offlineWakeAttemptsLocalRecoveryWithoutExtractingOrDownloading() = runTest {
        val network = MutableStateFlow(false)
        val fixture = fixture(backgroundScope, networkAvailable = network)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        fixture.runner.wake("service", 1, userInitiated = true)
        runCurrent()
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
        assertEquals(listOf(id), fixture.downloader.recoveryCalls)
        assertTrue(fixture.extractor.urls.isEmpty())
        assertTrue(fixture.downloader.calls.isEmpty())
        val acquired = async { fixture.runner.acquireBackground("worker") }
        runCurrent()
        assertFalse(acquired.await())
        fixture.runner.wake("service", 2, userInitiated = true)
        runCurrent()
        assertTrue(fixture.extractor.urls.isEmpty())
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
        assertEquals(listOf(id, id), fixture.downloader.recoveryCalls)
    }

    @Test fun extractionNetworkFailureParksBeforeDownloadingAndRecreatedRunnerResumesSameTask() = runTest {
        val network = MutableStateFlow(true)
        val fixture = fixture(backgroundScope, networkAvailable = network)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        fixture.extractor.failure = IOException("Unable to resolve host cdn.example")
        fixture.runner.wake("service", 1)
        runCurrent()
        network.value = false
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

    @Test fun unsupportedOrUnconfirmedResumeAtWindowExpiryRequiresForegroundResume() = runTest {
        for (canResume in listOf(false, null)) {
            val downloader = FakeDownloader().apply { resumeSupport = canResume }
            val fixture = fixture(backgroundScope, downloader)
            val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
            val window = async { fixture.runner.runBackgroundWindow("worker", 1_000) }
            runCurrent()
            downloader.calls.single().progress(DownloadProgress(DownloadPhase.DOWNLOADING, fraction = 0.2f))
            advanceTimeBy(1_000)
            runCurrent()
            window.await()

            assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
            assertEquals(QueuePauseKind.SYSTEM, fixture.queue.pauseReason()?.kind)
            val retryWindow = async { fixture.runner.runBackgroundWindow("worker-2", 1_000) }
            runCurrent()
            retryWindow.await()
            assertEquals(1, downloader.calls.size)

            fixture.runner.wake("service", 1, userInitiated = true)
            runCurrent()
            assertEquals(id, downloader.calls.last().id)
            assertEquals(2, downloader.calls.size)
            downloader.calls.last().succeed()
            runCurrent()
            assertEquals(DownloadStatus.COMPLETED, fixture.queue.get(id)?.status)
        }
    }

    @Test fun externallyCanceledNonResumableWindowWaitsForCleanupAndRequiresForeground() = runTest {
        val releaseCleanup = CompletableDeferred<Unit>()
        val downloader = FakeDownloader { _, _ ->
            try { awaitCancellation() } finally { withContext(NonCancellable) { releaseCleanup.await() } }
        }.apply { resumeSupport = false }
        val fixture = fixture(backgroundScope, downloader)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        val window = async { fixture.runner.runBackgroundWindow("worker", 10_000) }
        runCurrent()
        window.cancel()
        runCurrent()
        assertFalse(window.isCompleted)
        assertEquals(1, downloader.active)
        releaseCleanup.complete(Unit)
        runCurrent()
        assertTrue(window.isCompleted)
        assertEquals(0, downloader.active)
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertEquals(QueuePauseKind.SYSTEM, fixture.queue.pauseReason()?.kind)
    }

    @Test fun foregroundHandoverLetsNonResumableAttemptOutliveTheOldWorkerWindow() = runTest {
        val downloader = FakeDownloader().apply { resumeSupport = false }
        val fixture = fixture(backgroundScope, downloader)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        val window = async { fixture.runner.runBackgroundWindow("worker", 1_000) }
        runCurrent()
        fixture.runner.wake("service", 1, userInitiated = true)
        runCurrent()
        window.await()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, downloader.calls.size)
        assertEquals(DownloadRunner.Phase.RUNNING, fixture.runner.state.value.phase)
        assertNull(fixture.queue.pauseReason())
        downloader.calls.single().succeed()
        runCurrent()
        assertEquals(DownloadStatus.COMPLETED, fixture.queue.get(id)?.status)
    }

    @Test fun completedNonResumableAttemptDoesNotRequireForegroundForTheFollowingTask() = runTest {
        val listener = RecordingListener().apply { completionGate = CompletableDeferred() }
        val downloader = FakeDownloader().apply { resumeSupport = false }
        val fixture = fixture(backgroundScope, downloader, listener)
        val ids = fixture.queue.submitRequests(listOf(request("1"), request("2")))
        val window = async { fixture.runner.runBackgroundWindow("worker", 1_000) }
        runCurrent()
        downloader.calls.single().succeed()
        runCurrent()
        assertEquals(DownloadStatus.COMPLETED, fixture.queue.get(ids.first())?.status)

        advanceTimeBy(1_000)
        runCurrent()
        window.await()
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(ids.last())?.status)
        assertEquals(QueuePauseKind.NETWORK, fixture.queue.pauseReason()?.kind)
        assertEquals("content://output/${ids.first()}", fixture.queue.get(ids.first())?.outputUri)
    }

    @Test fun refreshingARejectedUrlClearsPreviousResumeSupportBeforeNewNativeWork() = runTest {
        val fixture = fixture(backgroundScope)
        val id = fixture.queue.submitPages(listOf(page(1)), FormatSelection.BestVideo).single()
        val window = async { fixture.runner.runBackgroundWindow("worker", 1_000) }
        runCurrent()
        fixture.downloader.resumeSupport = null
        fixture.downloader.calls.single().fail(IOException("HTTP Error 403"))
        runCurrent()
        assertEquals(2, fixture.downloader.calls.size)
        advanceTimeBy(1_000)
        runCurrent()
        window.await()
        assertEquals(DownloadStatus.QUEUED, fixture.queue.get(id)?.status)
        assertEquals(QueuePauseKind.SYSTEM, fixture.queue.pauseReason()?.kind)
    }

    @Test fun expiredCachedBackgroundUrlRefreshesOnlyTheSamePageOnce() = runTest {
        val network = MutableStateFlow(true)
        val fixture = fixture(backgroundScope, networkAvailable = network)
        val page = page(1)
        val id = fixture.queue.submitPages(listOf(page), FormatSelection.BestVideo).single()
        fixture.runner.wake("service", 1)
        runCurrent()
        val originalRequest = fixture.downloader.calls.single().request
        network.value = false
        fixture.downloader.calls.single().fail(IOException("Connection reset"))
        runCurrent()
        network.value = true
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
        var completionGate: CompletableDeferred<Unit>? = null
        var failureGate: CompletableDeferred<Unit>? = null
        override fun onStopped(taskId: String, task: DownloadTask?) { stopped += taskId }
        override suspend fun onFailed(task: DownloadTask, error: Throwable) { failed += task.id; failureGate?.await() }
        override suspend fun onCompleted(task: DownloadTask, output: DownloadOutput) {
            completionFailure?.let { throw it }
            completionGate?.await()
        }
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
        var resumeSupport: Boolean? = true
        val recoveryCalls = mutableListOf<String>()
        override suspend fun recover(processId: String, onProgress: (DownloadProgress) -> Unit): Result<DownloadOutput?> {
            recoveryCalls += processId
            if (recovered != null) onProgress(DownloadProgress(DownloadPhase.SAVING))
            return Result.success(recovered)
        }
        override suspend fun download(request: DownloadRequest, processId: String, onProgress: (DownloadProgress) -> Unit): Result<DownloadOutput> {
            resumeSupport?.let { onProgress(DownloadProgress(DownloadPhase.DOWNLOADING, canResume = it)) }
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
