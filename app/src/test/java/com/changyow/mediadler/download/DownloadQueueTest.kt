package com.changyow.mediadler.download

import com.changyow.mediadler.core.model.*
import com.changyow.mediadler.core.repo.DownloadQueueStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadQueueTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deletingFailedStorageAnchorKeepsPauseVisibleOnRemainingQueue() {
        val fixture = fixture()
        val ids = fixture.queue.submitPages(listOf(episode(1), episode(2)), FormatSelection.BestVideo)
        val claim = assertNotNull(fixture.queue.claimNext())
        val reason = QueuePause(QueuePauseKind.STORAGE, "空間不足")
        fixture.queue.pause(reason, claim.id)
        fixture.queue.fail(claim, reason.message)
        fixture.queue.remove(ids.first())
        assertNull(fixture.queue.get(ids.first()))
        assertEquals(reason, fixture.queue.get(ids.last())?.queuePause)
        assertNull(fixture.reopen().claimNext())
        fixture.queue.resume()
        assertEquals(ids.last(), fixture.queue.claimNext()?.id)
    }

    @Test fun pendingCleanupCannotHideTheOnlyPauseAndSilentlyBlockTheQueue() {
        val fixture = fixture()
        val queue = DownloadQueue(fixture.store, fixture.workRoot, beforeDelete = { false })
        val ids = queue.submitPages(listOf(episode(1), episode(2)), FormatSelection.BestVideo)
        val claim = assertNotNull(queue.claimNext())
        val reason = QueuePause(QueuePauseKind.STORAGE, "空間不足")
        queue.pause(reason, claim.id)
        queue.fail(claim, reason.message)
        queue.remove(ids.first())
        assertTrue(fixture.diskRecords().any { it.task.id == ids.first() && it.deleteRequested })
        assertEquals(reason, queue.pauseReason())
        queue.resume()
        assertEquals(ids.last(), queue.claimNext()?.id)
    }

    @Test fun pageBatchRetainsOrderedUnresolvedEpisodesAcrossRestart() {
        val fixture = fixture()
        val pages = listOf(1, 2, 10).map(::episode)
        val ids = fixture.queue.submitPages(pages, FormatSelection.CappedVideo(720))

        val restarted = fixture.reopen()
        assertEquals(ids, restarted.tasks.value.map { it.id })
        assertEquals(listOf(1, 2, 10), restarted.tasks.value.map { it.episodeNumber })
        assertEquals(listOf(1L, 2L, 3L), restarted.tasks.value.map { it.queuePosition })
        val stored = fixture.diskRecords()
        assertEquals(pages.map { QueuedSpec.Page(it, FormatSelection.CappedVideo(720)) }, stored.map { it.spec })
        assertTrue(stored.all { it.resolvedRequest == null && it.task.status == DownloadStatus.QUEUED })
        assertEquals(pages.first(), (assertNotNull(restarted.claimNext()).spec as QueuedSpec.Page).page)
    }

    @Test fun concurrentClaimsAllowOnlyOneActiveDownload() {
        val fixture = fixture()
        fixture.queue.submitPages(listOf(episode(1), episode(2), episode(10)), FormatSelection.BestVideo)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(8)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map {
                pool.submit<DownloadClaim?> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    fixture.queue.claimNext()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, results.map { it.get(5, TimeUnit.SECONDS) }.count { it != null })
            assertEquals(1, fixture.queue.activeCount())
            assertEquals(2, fixture.queue.tasks.value.count { it.status == DownloadStatus.QUEUED })
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun coldStartRecoversActiveTaskAndRejectsPreviousAttemptCallbacks() {
        val fixture = fixture()
        val id = fixture.queue.submitPages(listOf(episode(1)), FormatSelection.BestVideo).single()
        val oldClaim = assertNotNull(fixture.queue.claimNext())
        fixture.queue.setProgress(oldClaim, .35f, "old attempt")

        val restarted = fixture.reopen()
        assertEquals(DownloadStatus.QUEUED, assertNotNull(restarted.get(id)).status)
        assertEquals(-1f, assertNotNull(restarted.get(id)).progress)
        restarted.setProgress(oldClaim, .9f)
        assertFalse(restarted.complete(oldClaim, "content://stale/before-claim", "video/mp4"))
        val newClaim = assertNotNull(restarted.claimNext())
        assertEquals(id, newClaim.id)
        assertTrue(newClaim.attempt > oldClaim.attempt)
        val beforeStaleCallbacks = restarted.tasks.value
        restarted.setProgress(oldClaim, 1f, "stale")
        assertFalse(restarted.complete(oldClaim, "content://stale/after-claim", "video/mp4"))
        assertEquals(beforeStaleCallbacks, restarted.tasks.value)
        restarted.setProgress(newClaim, .1f)
        assertEquals(.1f, assertNotNull(restarted.get(id)).progress)
        assertNull(assertNotNull(restarted.get(id)).outputUri)
    }

    @Test fun failedRetryKeepsTaskIdentityAndPartialBytesAcrossRestart() {
        val fixture = fixture()
        val id = fixture.queue.submit(genericRequest())
        val claim = assertNotNull(fixture.queue.claimNext())
        val partial = fixture.partialFile(id)
        fixture.queue.fail(claim, "network disconnected")

        val restarted = fixture.reopen()
        assertEquals(DownloadStatus.FAILED, assertNotNull(restarted.get(id)).status)
        assertTrue(restarted.retry(id))
        val retry = assertNotNull(restarted.claimNext())
        assertEquals(id, retry.id)
        assertTrue(retry.attempt > claim.attempt)
        assertEquals(partial.parentFile, restarted.workDirectory(id))
        assertEquals("resumable bytes", partial.readText())
        assertNull(assertNotNull(restarted.get(id)).errorMessage)
    }

    @Test fun canceledRetryKeepsTaskIdentityAndPartialBytesAcrossRestart() {
        val fixture = fixture()
        val id = fixture.queue.submit(genericRequest())
        val claim = assertNotNull(fixture.queue.claimNext())
        val partial = fixture.partialFile(id)
        fixture.queue.cancel(id)
        fixture.queue.acknowledgeStop(claim)

        val restarted = fixture.reopen()
        assertEquals(DownloadStatus.CANCELED, assertNotNull(restarted.get(id)).status)
        assertTrue(restarted.retry(id))
        val retry = assertNotNull(restarted.claimNext())
        assertEquals(id, retry.id)
        assertTrue(retry.attempt > claim.attempt)
        assertEquals(partial.parentFile, restarted.workDirectory(id))
        assertEquals("resumable bytes", partial.readText())
    }

    @Test fun bulkRetryOnlyResetsFailedTasksInOneTransactionAndRetainsTheirIdentityAndBytes() {
        val fixture = fixture()
        val ids = fixture.queue.submitRequests(List(6) { genericRequest() })
        val failedClaims = (0..1).map { index ->
            assertNotNull(fixture.queue.claimNext()).also { claim ->
                fixture.queue.resolved(claim, genericRequest())
                fixture.queue.setProgress(claim, .4f, "partial transfer")
                fixture.queue.fail(claim, "network failure $index")
            }
        }
        val partials = failedClaims.map { fixture.partialFile(it.id) }
        val completed = assertNotNull(fixture.queue.claimNext())
        fixture.queue.complete(completed, "content://media/completed", "video/mp4")
        val canceled = assertNotNull(fixture.queue.claimNext())
        fixture.queue.cancel(canceled.id)
        fixture.queue.acknowledgeStop(canceled)
        val active = assertNotNull(fixture.queue.claimNext())
        fixture.queue.setProgress(active, .5f, "still downloading")
        val before = fixture.diskRecords().associateBy { it.task.id }
        val transactions = fixture.store.transactionCount

        assertEquals(2, fixture.queue.retryAllFailed())
        assertEquals(transactions + 1, fixture.store.transactionCount)
        val after = fixture.diskRecords().associateBy { it.task.id }
        failedClaims.forEach { claim ->
            val original = before.getValue(claim.id)
            val retried = after.getValue(claim.id)
            assertEquals(DownloadStatus.QUEUED, retried.task.status)
            assertEquals(-1f, retried.task.progress)
            assertNull(retried.task.errorMessage)
            assertNull(retried.task.progressDetail)
            assertFalse(retried.task.cancelRequested)
            assertEquals(original.task.queuePosition, retried.task.queuePosition)
            assertEquals(original.spec, retried.spec)
            assertEquals(original.resolvedRequest, retried.resolvedRequest)
            assertEquals(original.attempt, retried.attempt)
        }
        ids.drop(2).forEach { assertEquals(before[it], after[it]) }
        partials.forEach { assertEquals("resumable bytes", it.readText()) }
        assertEquals(0, fixture.queue.retryAllFailed())
        assertNull(fixture.queue.claimNext()) // Existing writer still owns its slot.
        fixture.queue.complete(active, "content://media/active-finished", "video/mp4")

        val restarted = fixture.reopen()
        assertEquals(ids.first(), assertNotNull(restarted.claimNext()).id)
        assertEquals(DownloadStatus.QUEUED, assertNotNull(restarted.get(ids[1])).status)
        partials.forEach { assertEquals("resumable bytes", it.readText()) }
    }

    @Test fun failedBulkRetryCommitLeavesEveryTaskUnchanged() {
        val fixture = fixture()
        repeat(2) { fixture.queue.submit(genericRequest()) }
        repeat(2) { fixture.queue.fail(assertNotNull(fixture.queue.claimNext()), "network") }
        val before = fixture.queue.tasks.value
        val stored = fixture.diskRecords()
        fixture.store.failNextCommit = true
        assertFailsWith<CommitFailure> { fixture.queue.retryAllFailed() }
        assertEquals(before, fixture.queue.tasks.value)
        assertEquals(stored, fixture.diskRecords())
        assertEquals(2, fixture.queue.retryAllFailed())
    }

    @Test fun bulkRetryDoesNotRevivePendingDeletion() {
        val fixture = fixture()
        val ids = fixture.queue.submitRequests(List(2) { genericRequest() })
        repeat(2) { fixture.queue.fail(assertNotNull(fixture.queue.claimNext()), "network") }
        fixture.store.transact { records -> records.map {
            if (it.task.id == ids.first()) it.copy(deleteRequested = true) else it
        } }
        val deleting = fixture.diskRecords().first()
        assertEquals(1, fixture.queue.retryAllFailed())
        assertEquals(deleting, fixture.diskRecords().first())
        assertNull(fixture.queue.get(ids.first()))
        assertEquals(DownloadStatus.QUEUED, assertNotNull(fixture.queue.get(ids.last())).status)
    }

    @Test fun activeCancellationOccupiesSlotUntilAcknowledged() {
        val fixture = fixture()
        val first = fixture.queue.submit(genericRequest())
        val second = fixture.queue.submit(genericRequest())
        val claim = assertNotNull(fixture.queue.claimNext())
        fixture.queue.cancel(first)

        assertTrue(assertNotNull(fixture.queue.get(first)).cancelRequested)
        assertEquals(1, fixture.queue.activeCount())
        assertNull(fixture.queue.claimNext())
        fixture.queue.setProgress(claim, .9f)
        assertEquals(-1f, assertNotNull(fixture.queue.get(first)).progress)
        fixture.queue.acknowledgeStop(claim)
        assertEquals(DownloadStatus.CANCELED, assertNotNull(fixture.queue.get(first)).status)
        assertEquals(second, assertNotNull(fixture.queue.claimNext()).id)
    }

    @Test fun restartWhileCancellationIsPendingDoesNotRequeueCanceledTask() {
        val fixture = fixture()
        val first = fixture.queue.submit(genericRequest())
        val second = fixture.queue.submit(genericRequest())
        val claim = assertNotNull(fixture.queue.claimNext())
        val partial = fixture.partialFile(first)
        fixture.queue.cancel(first)

        val restarted = fixture.reopen()
        assertEquals(DownloadStatus.CANCELED, assertNotNull(restarted.get(first)).status)
        assertFalse(assertNotNull(restarted.get(first)).cancelRequested)
        assertEquals(second, assertNotNull(restarted.claimNext()).id)
        restarted.acknowledgeStop(claim, interrupted = true)
        assertEquals(DownloadStatus.CANCELED, assertNotNull(restarted.get(first)).status)
        assertEquals("resumable bytes", partial.readText())
    }

    @Test fun activeRemovalWaitsForNativeStopBeforeDeletingPrivateScratch() {
        val fixture = fixture()
        val first = fixture.queue.submit(genericRequest())
        val second = fixture.queue.submit(genericRequest())
        val claim = assertNotNull(fixture.queue.claimNext())
        val partial = fixture.partialFile(first)
        fixture.queue.remove(first)

        assertNotNull(fixture.queue.get(first))
        assertTrue(partial.exists())
        assertTrue(fixture.diskRecords().first { it.task.id == first }.deleteRequested)
        assertNull(fixture.queue.claimNext())
        fixture.queue.acknowledgeStop(claim)
        assertNull(fixture.queue.get(first))
        assertFalse(requireNotNull(partial.parentFile).exists())
        assertEquals(second, assertNotNull(fixture.queue.claimNext()).id)
    }

    @Test fun completedTaskStaysCompletedAfterStopAcknowledgementAndPublicOutputSurvivesRemoval() {
        val fixture = fixture()
        val id = fixture.queue.submit(genericRequest())
        val claim = assertNotNull(fixture.queue.claimNext())
        val partial = fixture.partialFile(id)
        val publicFile = temporary.newFile("public-video.mp4").apply { writeText("published video") }
        val outputUri = publicFile.toURI().toString()
        assertTrue(fixture.queue.complete(claim, outputUri, "video/mp4"))
        assertFalse(requireNotNull(partial.parentFile).exists())
        fixture.queue.acknowledgeStop(claim)
        fixture.queue.acknowledgeStop(claim, interrupted = true)

        val restarted = fixture.reopen()
        assertEquals(DownloadStatus.COMPLETED, assertNotNull(restarted.get(id)).status)
        assertEquals(outputUri, assertNotNull(restarted.get(id)).outputUri)
        assertNull(restarted.claimNext())
        restarted.remove(id)
        assertNull(restarted.get(id))
        assertEquals("published video", publicFile.readText())
    }

    @Test fun specialAliasesAndQueryChangesReuseTheSameActiveTask() {
        val aliases = listOf(
            listOf("https://gimytv.io/eps/12345-1-2.html?share=a", "https://www.gimytw.cc/eps/12345-1-2.html?share=b") to SourceKind.GIMY,
            listOf("https://play.8maple.st/play/OD4C-2-4.html?share=a", "https://www.8maple.st/play/OD4C-2-4.html?share=b") to SourceKind.EIGHT_MAPLE,
        )
        aliases.forEach { (urls, kind) ->
            val fixture = fixture()
            val id = fixture.queue.submitPages(listOf(EpisodePage(urls.first(), kind)), FormatSelection.BestVideo).single()
            assertEquals(id, assertNotNull(fixture.queue.claimNext()).id)
            val repeated = fixture.queue.submitPages(listOf(EpisodePage(urls.last(), kind)), FormatSelection.BestVideo).single()
            assertEquals(id, repeated)
            assertEquals(listOf(id), fixture.reopen().tasks.value.map { it.id })
        }
    }

    @Test fun generalResolvedRequestsStillAllowDuplicateSubmissions() {
        val fixture = fixture()
        val first = fixture.queue.submit(genericRequest())
        val second = fixture.queue.submit(genericRequest())
        assertNotEquals(first, second)
        assertEquals(listOf(first, second), fixture.reopen().tasks.value.map { it.id })
        assertTrue(fixture.diskRecords().all { it.spec is QueuedSpec.Resolved })
    }

    @Test fun requestBatchCommitsOnceAndCommitFailureRejectsTheWholeBatch() {
        val fixture = fixture()
        val beforeSuccess = fixture.store.transactionCount
        val ids = fixture.queue.submitRequests(listOf(genericRequest(), genericRequest()))
        assertEquals(beforeSuccess + 1, fixture.store.transactionCount)
        assertEquals(2, ids.distinct().size)
        assertEquals(ids, fixture.diskRecords().map { it.task.id })

        val committed = fixture.diskRecords()
        val projected = fixture.queue.tasks.value
        val beforeFailure = fixture.store.transactionCount
        fixture.store.failNextCommit = true
        assertFailsWith<CommitFailure> {
            fixture.queue.submitRequests(listOf(genericRequest(), genericRequest()))
        }
        assertEquals(beforeFailure + 1, fixture.store.transactionCount)
        assertEquals(committed, fixture.diskRecords())
        assertEquals(projected, fixture.queue.tasks.value)
    }

    @Test fun failedTombstoneRemovalCommitIsRecoveredOnRestartWithoutAffectingOtherTasks() {
        val fixture = fixture()
        val first = fixture.queue.submit(genericRequest())
        val second = fixture.queue.submit(genericRequest())
        val claim = assertNotNull(fixture.queue.claimNext())
        val partial = fixture.partialFile(first)
        fixture.queue.remove(first)
        val otherRecord = fixture.diskRecords().single { it.task.id == second }
        fixture.store.failOnTransaction = fixture.store.transactionCount + 2

        assertFailsWith<CommitFailure> { fixture.queue.acknowledgeStop(claim) }
        assertFalse(requireNotNull(partial.parentFile).exists())
        val tombstone = fixture.diskRecords().single { it.task.id == first }
        assertTrue(tombstone.deleteRequested)
        assertEquals(DownloadStatus.CANCELED, tombstone.task.status)
        assertEquals(otherRecord, fixture.diskRecords().single { it.task.id == second })

        val restarted = fixture.reopen()
        assertNull(restarted.get(first))
        assertEquals(listOf(otherRecord), fixture.diskRecords())
        assertEquals(listOf(second), restarted.tasks.value.map { it.id })
        assertEquals(second, assertNotNull(restarted.claimNext()).id)
    }

    @Test fun staleCompletionAfterNewAttemptCompletesIsRejectedAndKeepsPublishedUri() {
        val fixture = fixture()
        val id = fixture.queue.submit(genericRequest())
        val oldClaim = assertNotNull(fixture.queue.claimNext())
        fixture.queue.fail(oldClaim, "retry required")
        assertTrue(fixture.queue.retry(id))
        val currentClaim = assertNotNull(fixture.queue.claimNext())
        assertTrue(currentClaim.attempt > oldClaim.attempt)
        val currentUri = "content://media/external/video/current"
        assertTrue(fixture.queue.complete(currentClaim, currentUri, "video/mp4"))
        val completed = fixture.diskRecords()

        assertFalse(fixture.queue.complete(oldClaim, "content://media/external/video/stale", "video/webm"))
        assertEquals(DownloadStatus.COMPLETED, assertNotNull(fixture.queue.get(id)).status)
        assertEquals(currentUri, assertNotNull(fixture.queue.get(id)).outputUri)
        assertEquals("video/mp4", assertNotNull(fixture.queue.get(id)).mimeType)
        assertEquals(completed, fixture.diskRecords())
    }

    @Test fun failedStoreCommitLeavesUiAndPersistedSnapshotUnchanged() {
        val fixture = fixture()
        fixture.queue.submit(genericRequest())
        val beforeSubmit = fixture.queue.tasks.value
        fixture.store.failNextCommit = true
        assertFailsWith<CommitFailure> { fixture.queue.submit(genericRequest()) }
        assertEquals(beforeSubmit, fixture.queue.tasks.value)
        assertEquals(beforeSubmit, fixture.diskRecords().map { it.task })

        val claim = assertNotNull(fixture.queue.claimNext())
        val beforeProgress = fixture.queue.tasks.value
        fixture.store.failNextCommit = true
        assertFailsWith<CommitFailure> { fixture.queue.setProgress(claim, .5f) }
        assertEquals(beforeProgress, fixture.queue.tasks.value)
        assertEquals(beforeProgress, fixture.diskRecords().map { it.task })
    }

    @Test fun genericSpecificFormatAndResourceMetadataRoundTripThroughDisk() {
        val fixture = fixture()
        val request = genericRequest()
        val id = fixture.queue.submit(request)
        val restarted = fixture.reopen()
        val stored = fixture.diskRecords().single()
        assertEquals(QueuedSpec.Resolved(request), stored.spec)
        assertNull(stored.resolvedRequest)
        assertEquals(QueuedSpec.Resolved(request), assertNotNull(restarted.claimNext()).spec)
        val task = assertNotNull(restarted.get(id))
        assertEquals(request.item.originalTitle, task.originalTitle)
        assertEquals(request.item.seriesTitle, task.seriesTitle)
        assertEquals(request.item.seasonNumber, task.seasonNumber)
        assertEquals(request.item.episodeNumber, task.episodeNumber)
        assertEquals(request.item.source.originalUrl, task.sourceUrl)
        assertEquals(request.item.source.pageUrl, task.pageUrl)
    }

    @Test fun nonActiveSpecialMetadataEditsPersistWithLineAndLeaveOutputsAndScratchUntouched() {
        for (status in listOf(DownloadStatus.QUEUED, DownloadStatus.FAILED, DownloadStatus.CANCELED, DownloadStatus.COMPLETED)) {
            val fixture = fixture()
            val page = episode(4)
            val id = fixture.queue.submitPages(listOf(page), FormatSelection.BestVideo).single()
            if (status != DownloadStatus.QUEUED) {
                val claim = assertNotNull(fixture.queue.claimNext())
                val request = genericRequest().let { it.copy(item = it.item.copy(
                    source = SourceContext(page.originalUrl, page.playbackUrl, SourceKind.EIGHT_MAPLE))) }
                fixture.queue.resolved(claim, request)
                when (status) {
                    DownloadStatus.FAILED -> fixture.queue.fail(claim, "network")
                    DownloadStatus.CANCELED -> { fixture.queue.cancel(id); fixture.queue.acknowledgeStop(claim) }
                    DownloadStatus.COMPLETED -> fixture.queue.complete(claim, "content://public/unchanged", "video/mp4")
                    else -> error("unexpected test state")
                }
            }
            val pendingArtifact = File(fixture.queue.workDirectory(id), "pending-artifact.json").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("immutable previously selected destination")
            }
            val original = assertNotNull(fixture.queue.get(id))
            val correction = EpisodeMetadata("修正標題", null, null, 7)
            assertTrue(fixture.queue.editMetadata(id, correction))
            assertEquals("immutable previously selected destination", pendingArtifact.readText())
            val restarted = fixture.reopen()
            val updated = assertNotNull(restarted.get(id))
            assertEquals(status, updated.status)
            assertEquals(correction.applyTo(original), updated)
            assertEquals("2", updated.lineId)
            assertEquals(page.lineName, updated.lineName)
            val record = fixture.diskRecords().single()
            assertEquals(correction, (record.spec as QueuedSpec.Page).page.metadataOverride)
            record.resolvedRequest?.let {
                assertEquals("修正標題", it.item.title)
                assertNull(it.item.seriesTitle)
                assertNull(it.item.seasonNumber)
                assertEquals(7, it.item.episodeNumber)
            }
            // Restart intentionally cleans completed scratch; metadata edits themselves must not.
            if (status != DownloadStatus.COMPLETED) {
                assertEquals("immutable previously selected destination", pendingArtifact.readText())
            }
            assertEquals(original.outputUri, updated.outputUri)
        }
    }

    @Test fun activeOrGeneralTasksCannotBeEditedAndRejectedEditDoesNotChangeStoredRecord() {
        val fixture = fixture()
        val id = fixture.queue.submitPages(listOf(episode(4)), FormatSelection.BestVideo).single()
        val claim = assertNotNull(fixture.queue.claimNext())
        val correction = EpisodeMetadata("禁止修改", "影集", 1, 1)
        val before = fixture.diskRecords()
        assertFalse(fixture.queue.editMetadata(id, correction))
        assertEquals(before, fixture.diskRecords())
        fixture.queue.setProgress(claim, .3f)
        assertFalse(fixture.queue.editMetadata(id, correction))
        fixture.queue.processing(claim)
        assertFalse(fixture.queue.editMetadata(id, correction))
        val general = fixture.queue.submit(genericRequest())
        assertFalse(fixture.queue.editMetadata(general, correction))
    }

    @Test fun networkWaitIsAtomicDurableAndRetainsTheSameCheckpointAndFifoPosition() {
        val fixture = fixture()
        val ids = fixture.queue.submitRequests(listOf(genericRequest(), genericRequest()))
        val claim = assertNotNull(fixture.queue.claimNext())
        fixture.queue.setProgress(claim, .4f, "partial")
        val partial = fixture.partialFile(claim.id)
        val before = fixture.diskRecords()
        fixture.store.failNextCommit = true
        assertFailsWith<CommitFailure> { fixture.queue.parkNetwork(claim) }
        assertEquals(before, fixture.diskRecords())
        assertEquals(before.map { it.task }, fixture.queue.tasks.value)
        assertTrue(fixture.queue.parkNetwork(claim))
        val restarted = fixture.reopen()
        assertEquals(QueuePauseKind.NETWORK, restarted.pauseReason()?.kind)
        assertEquals(DownloadStatus.QUEUED, restarted.get(claim.id)?.status)
        assertEquals(.4f, restarted.get(claim.id)?.progress)
        assertNotNull(restarted.get(claim.id)?.progressDetail)
        assertNull(restarted.claimNext())
        restarted.resumeNetwork()
        val resumed = assertNotNull(restarted.claimNext())
        assertEquals(ids.first(), resumed.id)
        assertTrue(resumed.attempt > claim.attempt)
        assertEquals("resumable bytes", partial.readText())
        assertEquals(ids, restarted.tasks.value.map { it.id })
        assertFalse(restarted.parkNetwork(claim)) // Stale callbacks cannot park a later attempt.
    }

    @Test fun cancelOrDeleteNetworkWaitTransfersItsAnchorAndNeverRevivesTheCanceledTask() {
        for (delete in listOf(false, true)) {
            val fixture = fixture()
            val ids = fixture.queue.submitRequests(listOf(genericRequest(), genericRequest()))
            val claim = assertNotNull(fixture.queue.claimNext())
            fixture.queue.parkNetwork(claim)
            if (delete) fixture.queue.remove(ids.first()) else fixture.queue.cancel(ids.first())
            val restarted = fixture.reopen()
            assertEquals(QueuePauseKind.NETWORK, restarted.get(ids.last())?.queuePause?.kind)
            assertNull(restarted.get(ids.first())?.queuePause)
            restarted.resumeNetwork()
            assertEquals(ids.last(), restarted.claimNext()?.id)
            assertTrue(restarted.get(ids.first())?.status in listOf(null, DownloadStatus.CANCELED))
        }
        val fixture = fixture()
        val id = fixture.queue.submit(genericRequest())
        fixture.queue.parkNetwork(assertNotNull(fixture.queue.claimNext()))
        fixture.queue.cancel(id)
        assertNull(fixture.queue.pauseReason())
        fixture.queue.resumeNetwork()
        assertEquals(DownloadStatus.CANCELED, fixture.queue.get(id)?.status)
        assertNull(fixture.queue.claimNext())
    }

    @Test fun networkPauseAndAutomaticResumeNeverReplaceSystemOrStoragePause() {
        for (kind in listOf(QueuePauseKind.SYSTEM, QueuePauseKind.STORAGE)) {
            val fixture = fixture()
            fixture.queue.submit(genericRequest())
            val claim = assertNotNull(fixture.queue.claimNext())
            val stronger = QueuePause(kind, "manual resume required")
            fixture.queue.pause(stronger, claim.id)
            assertTrue(fixture.queue.parkNetwork(claim))
            fixture.queue.pause(QueuePause(QueuePauseKind.NETWORK, "network"), claim.id)
            fixture.queue.resumeNetwork()
            assertEquals(stronger, fixture.queue.pauseReason())
            assertNull(fixture.queue.claimNext())
        }
    }

    @Test fun manualPageRetryRequiresFreshResolutionEvenWhenRecoveryWorkerClaimsFirst() {
        for (status in listOf(DownloadStatus.FAILED, DownloadStatus.CANCELED)) {
            val fixture = fixture()
            val page = episode(1)
            val id = fixture.queue.submitPages(listOf(page), FormatSelection.BestVideo).single()
            val original = assertNotNull(fixture.queue.claimNext())
            val request = genericRequest().let { it.copy(item = it.item.copy(
                source = SourceContext(page.originalUrl, page.playbackUrl, page.kind))) }
            fixture.queue.resolved(original, request)
            if (status == DownloadStatus.FAILED) fixture.queue.fail(original, "HTTP 404") else {
                fixture.queue.cancel(id)
                fixture.queue.acknowledgeStop(original)
            }

            assertTrue(fixture.queue.retry(id))
            val automatic = assertNotNull(fixture.reopen().claimNext(preferResolved = true))
            assertEquals(id, automatic.id)
            assertEquals(original.spec, automatic.spec)
            assertNull(automatic.resolvedRequest)
            assertNull(fixture.diskRecords().single().resolvedRequest)
        }
    }

    @Test fun bulkPageRetryRequiresFreshResolutionEvenWhenRecoveryWorkerClaimsFirst() {
        val fixture = fixture()
        val pages = listOf(episode(1), episode(2))
        val ids = fixture.queue.submitPages(pages, FormatSelection.BestVideo)
        pages.forEach { page ->
            val original = assertNotNull(fixture.queue.claimNext())
            val request = genericRequest().let { it.copy(item = it.item.copy(
                source = SourceContext(page.originalUrl, page.playbackUrl, page.kind))) }
            fixture.queue.resolved(original, request)
            fixture.queue.fail(original, "HTTP 404")
        }

        assertEquals(2, fixture.queue.retryAllFailed())
        assertTrue(fixture.diskRecords().all { it.resolvedRequest == null })
        val restarted = fixture.reopen()
        ids.forEach { id ->
            val automatic = assertNotNull(restarted.claimNext(preferResolved = true))
            assertEquals(id, automatic.id)
            assertNull(automatic.resolvedRequest)
            restarted.complete(automatic, "content://media/$id", "video/mp4")
        }
    }

    @Test fun onlyAutomaticClaimsReuseTheDurableResolvedPageRequest() {
        val fixture = fixture()
        val page = episode(1)
        val id = fixture.queue.submitPages(listOf(page), FormatSelection.BestVideo).single()
        val original = assertNotNull(fixture.queue.claimNext())
        val request = genericRequest().let { it.copy(item = it.item.copy(
            source = SourceContext(page.originalUrl, page.playbackUrl, page.kind))) }
        fixture.queue.resolved(original, request)
        fixture.queue.parkNetwork(original)

        val reopened = fixture.reopen()
        reopened.resumeNetwork()
        val automatic = assertNotNull(reopened.claimNext(preferResolved = true))
        assertEquals(id, automatic.id)
        assertEquals(request, automatic.resolvedRequest)
        reopened.parkNetwork(automatic)
        reopened.resumeNetwork()
        val manual = assertNotNull(reopened.claimNext())
        assertEquals(id, manual.id)
        assertNull(manual.resolvedRequest)
        assertEquals(request, fixture.diskRecords().single().resolvedRequest)
    }

    @Test fun queuedPauseSurvivesRestartAndOnlyExplicitResumeMakesItClaimable() {
        val fixture = fixture()
        val ids = fixture.queue.submitRequests(List(2) { genericRequest() })
        val partial = fixture.partialFile(ids.first())
        assertTrue(fixture.queue.pauseTask(ids.first()))
        assertTrue(fixture.queue.pauseTask(ids.first()))
        val reopened = fixture.reopen()
        assertEquals(DownloadStatus.PAUSED, reopened.get(ids.first())?.status)
        assertEquals(ids.last(), reopened.claimNext()?.id)
        assertTrue(reopened.resumeTask(ids.first()))
        assertFalse(reopened.resumeTask(ids.first()))
        assertEquals(DownloadStatus.QUEUED, reopened.get(ids.first())?.status)
        assertEquals("resumable bytes", partial.readText())
    }

    @Test fun activePauseWaitsForNativeExitRejectsLateProgressAndResumesTheCachedSource() {
        for (recreate in listOf(false, true)) {
            val fixture = fixture()
            val page = episode(1)
            val id = fixture.queue.submitPages(listOf(page), FormatSelection.BestVideo).single()
            val claim = assertNotNull(fixture.queue.claimNext())
            val request = genericRequest()
            fixture.queue.resolved(claim, request)
            fixture.queue.setProgress(claim, DownloadProgress(DownloadPhase.DOWNLOADING, .4f, "partial", canResume = true))
            fixture.queue.setProgress(claim, DownloadProgress(DownloadPhase.DOWNLOADING, .45f, "partial"))
            val partial = fixture.partialFile(id)
            assertTrue(fixture.queue.pauseTask(id))
            assertTrue(fixture.queue.pauseTask(id))
            assertEquals(DownloadStatus.DOWNLOADING, fixture.queue.get(id)?.status)
            assertEquals(ItemInterruption.MANUAL_PAUSE, fixture.queue.get(id)?.interruption)
            val stopped = fixture.diskRecords()
            fixture.queue.setProgress(claim, DownloadProgress(DownloadPhase.PROCESSING, 1f))
            fixture.queue.processing(claim)
            fixture.queue.resolved(claim, genericRequest())
            assertFalse(fixture.queue.parkNetwork(claim))
            assertFalse(fixture.queue.complete(claim, "content://late", "video/mp4"))
            assertEquals(stopped, fixture.diskRecords())
            assertNull(fixture.queue.claimNext())
            val queue = if (recreate) fixture.reopen() else fixture.queue.also { it.acknowledgeStop(claim) }
            assertEquals(DownloadStatus.PAUSED, queue.get(id)?.status)
            assertEquals(.45f, queue.get(id)?.progress)
            assertEquals(true, queue.get(id)?.canResume)
            assertNull(queue.get(id)?.interruption)
            assertTrue(queue.resumeTask(id))
            val resumed = assertNotNull(queue.claimNext(preferResolved = false))
            assertEquals(id, resumed.id)
            assertTrue(resumed.attempt > claim.attempt)
            assertEquals(request, resumed.resolvedRequest)
            queue.setProgress(claim, .99f)
            assertEquals(.45f, queue.get(id)?.progress)
            assertEquals("resumable bytes", partial.readText())
        }
    }

    @Test fun processDeathAfterResumedClaimStillReusesTheOriginalTemporarySource() {
        val fixture = fixture()
        val page = episode(1)
        val id = fixture.queue.submitPages(listOf(page), FormatSelection.BestVideo).single()
        val original = assertNotNull(fixture.queue.claimNext())
        val request = genericRequest()
        fixture.queue.resolved(original, request)
        fixture.queue.setProgress(original, .4f)
        val partial = fixture.partialFile(id)
        assertTrue(fixture.queue.pauseTask(id))
        fixture.queue.acknowledgeStop(original)
        assertTrue(fixture.queue.resumeTask(id))
        val resumed = assertNotNull(fixture.queue.claimNext(preferResolved = false))
        assertEquals(request, resumed.resolvedRequest)
        assertFalse(fixture.diskRecords().single().reuseResolvedRequest)

        val recreated = fixture.reopen()
        val recovered = assertNotNull(recreated.claimNext(preferResolved = false))
        assertEquals(id, recovered.id)
        assertTrue(recovered.attempt > resumed.attempt)
        assertEquals(request, recovered.resolvedRequest)
        assertEquals(fixture.queue.workDirectory(id), recreated.workDirectory(recovered.id))
        assertEquals("resumable bytes", partial.readText())
    }

    @Test fun downloadNowAtomicallyPlacesTargetThenInterruptedActiveThenTheStableRemainder() {
        for (recreate in listOf(false, true)) {
            val fixture = fixture()
            val ids = fixture.queue.submitRequests(List(5) { genericRequest() })
            val history = assertNotNull(fixture.queue.claimNext())
            fixture.queue.complete(history, "content://history", "video/mp4")
            val completed = fixture.diskRecords().first()
            val active = assertNotNull(fixture.queue.claimNext())
            val request = genericRequest()
            fixture.queue.resolved(active, request)
            fixture.queue.setProgress(active, .3f)
            val partial = fixture.partialFile(active.id)
            assertTrue(fixture.queue.pauseTask(ids[3]))
            val transactions = fixture.store.transactionCount
            assertEquals(DownloadNowResult.PREEMPTING, fixture.queue.downloadNow(ids[3]))
            assertEquals(transactions + 1, fixture.store.transactionCount)
            val order = listOf(ids[3], ids[1], ids[2], ids[4])
            assertEquals(order, fixture.queue.tasks.value.filterNot { it.status.isTerminal }.map { it.id })
            assertEquals(completed, fixture.diskRecords().first())
            assertEquals(DownloadStatus.QUEUED, fixture.queue.get(ids[3])?.status)
            assertEquals(ItemInterruption.PRIORITY_REQUEUE, fixture.queue.get(active.id)?.interruption)
            assertEquals(DownloadStatus.DOWNLOADING, fixture.queue.get(active.id)?.status)
            assertNull(fixture.queue.claimNext())
            assertEquals(DownloadNowResult.PREEMPTING, fixture.queue.downloadNow(ids[3]))
            val queue = if (recreate) fixture.reopen() else fixture.queue.also { it.acknowledgeStop(active) }
            assertEquals(order, queue.tasks.value.filterNot { it.status.isTerminal }.map { it.id })
            assertEquals(.3f, queue.get(active.id)?.progress)
            val chosen = assertNotNull(queue.claimNext())
            assertEquals(ids[3], chosen.id)
            queue.complete(chosen, "content://chosen", "video/mp4")
            val resumed = assertNotNull(queue.claimNext(preferResolved = false))
            assertEquals(active.id, resumed.id)
            assertEquals(request, resumed.resolvedRequest)
            assertTrue(resumed.attempt > active.attempt)
            assertEquals("resumable bytes", partial.readText())
            assertEquals(ids.size, queue.tasks.value.map { it.id }.distinct().size)
        }
    }

    @Test fun processingRejectsPauseAndFinishesBeforeTheDownloadNowTarget() {
        val fixture = fixture()
        val ids = fixture.queue.submitRequests(List(3) { genericRequest() })
        val active = assertNotNull(fixture.queue.claimNext())
        fixture.queue.processing(active)
        assertFalse(fixture.queue.pauseTask(active.id))
        assertEquals(DownloadNowResult.READY, fixture.queue.downloadNow(active.id))
        assertEquals(DownloadNowResult.AFTER_PROCESSING, fixture.queue.downloadNow(ids.last()))
        assertNull(fixture.queue.get(active.id)?.interruption)
        assertNull(fixture.queue.claimNext())
        assertTrue(fixture.queue.complete(active, "content://saved", "video/mp4"))
        assertEquals(ids.last(), fixture.queue.claimNext()?.id)
    }

    @Test fun downloadNowResumesOnlyTheSelectedPausedTaskAndPreservesGlobalGates() {
        for (kind in listOf(QueuePauseKind.STORAGE, QueuePauseKind.SYSTEM)) {
            val fixture = fixture()
            val ids = fixture.queue.submitRequests(List(3) { genericRequest() })
            ids.take(2).forEach { assertTrue(fixture.queue.pauseTask(it)) }
            fixture.queue.pause(QueuePause(kind, "explicit resume required"), ids.last())
            assertEquals(DownloadNowResult.READY, fixture.queue.downloadNow(ids[1]))
            assertEquals(DownloadStatus.PAUSED, fixture.queue.get(ids.first())?.status)
            assertEquals(DownloadStatus.QUEUED, fixture.queue.get(ids[1])?.status)
            assertEquals(kind, fixture.queue.pauseReason()?.kind)
            assertNull(fixture.queue.claimNext())
            assertTrue(fixture.queue.resumeTask(ids.first()))
            assertEquals(kind, fixture.queue.pauseReason()?.kind)
        }
    }

    @Test fun cancelAndDeleteWinOverPendingPauseOrPriorityOnAcknowledgementAndRestart() {
        for (intent in ItemInterruption.entries) for (delete in listOf(false, true)) for (recreate in listOf(false, true)) {
            val fixture = fixture()
            val ids = fixture.queue.submitRequests(List(2) { genericRequest() })
            val active = assertNotNull(fixture.queue.claimNext())
            val partial = fixture.partialFile(active.id)
            when (intent) {
                ItemInterruption.MANUAL_PAUSE -> fixture.queue.pauseTask(active.id)
                ItemInterruption.PRIORITY_REQUEUE -> fixture.queue.downloadNow(ids.last())
                ItemInterruption.SKIP_AD_ANALYSIS -> {
                    fixture.queue.setProgress(active, DownloadProgress(DownloadPhase.AD_ANALYSIS))
                    assertTrue(fixture.queue.skipAdAnalysis(active.id))
                }
            }
            if (delete) fixture.queue.remove(active.id) else fixture.queue.cancel(active.id)
            assertFalse(fixture.queue.pauseTask(active.id))
            val queue = if (recreate) fixture.reopen() else fixture.queue.also { it.acknowledgeStop(active, interrupted = true) }
            if (delete) {
                assertNull(queue.get(active.id))
                assertFalse(partial.exists())
            } else {
                assertEquals(DownloadStatus.CANCELED, queue.get(active.id)?.status)
                assertNull(queue.get(active.id)?.interruption)
                assertEquals("resumable bytes", partial.readText())
            }
            assertEquals(ids.last(), queue.claimNext()?.id)
        }
    }

    @Test fun manualPauseTakesPrecedenceOverPriorityRequeue() {
        for (pauseFirst in listOf(false, true)) {
            val fixture = fixture()
            val ids = fixture.queue.submitRequests(List(2) { genericRequest() })
            val active = assertNotNull(fixture.queue.claimNext())
            if (pauseFirst) fixture.queue.pauseTask(active.id)
            fixture.queue.downloadNow(ids.last())
            if (!pauseFirst) fixture.queue.pauseTask(active.id)
            fixture.queue.acknowledgeStop(active, interrupted = true)
            assertEquals(DownloadStatus.PAUSED, fixture.queue.get(active.id)?.status)
            assertEquals(ids.last(), fixture.queue.claimNext()?.id)
        }
    }

    @Test fun rejectedAndFailedItemCommandsCannotPartiallyChangeTheQueue() {
        val fixture = fixture()
        val ids = fixture.queue.submitRequests(List(2) { genericRequest() })
        assertFalse(fixture.queue.pauseTask("missing"))
        assertFalse(fixture.queue.resumeTask("missing"))
        assertEquals(DownloadNowResult.REJECTED, fixture.queue.downloadNow("missing"))
        val active = assertNotNull(fixture.queue.claimNext())
        fixture.queue.setProgress(active, DownloadProgress(DownloadPhase.AD_ANALYSIS))
        val before = fixture.diskRecords()
        for (readFailure in listOf(false, true)) for (command in listOf<() -> Any>(
            { fixture.queue.pauseTask(active.id) }, { fixture.queue.downloadNow(ids.last()) },
            { fixture.queue.skipAdAnalysis(active.id) },
        )) {
            if (readFailure) fixture.store.failNextRead = true else fixture.store.failNextCommit = true
            assertFailsWith<CommitFailure> { command() }
            assertEquals(before, fixture.diskRecords())
            assertEquals(before.map { it.task }, fixture.queue.tasks.value)
        }
        fixture.queue.pauseTask(active.id)
        fixture.queue.acknowledgeStop(active)
        val paused = fixture.diskRecords()
        fixture.store.failNextCommit = true
        assertFailsWith<CommitFailure> { fixture.queue.resumeTask(active.id) }
        assertEquals(paused, fixture.diskRecords())
        assertEquals(paused.map { it.task }, fixture.queue.tasks.value)
        fixture.queue.cancel(active.id)
        assertFalse(fixture.queue.pauseTask(active.id))
        assertFalse(fixture.queue.resumeTask(active.id))
        assertEquals(DownloadNowResult.REJECTED, fixture.queue.downloadNow(active.id))
    }

    @Test fun oldSerializedQueuePayloadDecodesWithNoItemInterruptionOrResumeFlag() {
        val fixture = fixture()
        fixture.queue.submit(genericRequest())
        val old = fixture.file.readText().replace(",\"reuseResolvedRequest\":false", "")
            .replace(",\"interruption\":null", "").replace(",\"canResume\":null", "")
            .replace(",\"progressPhase\":null", "").replace(",\"skipAdAnalysis\":false", "")
        fixture.file.writeText(old)
        val record = fixture.diskRecords().single()
        assertFalse(record.reuseResolvedRequest)
        assertNull(record.task.interruption)
        assertNull(record.task.canResume)
        assertNull(record.task.progressPhase)
        assertFalse(record.task.skipAdAnalysis)
        assertFalse((record.spec as QueuedSpec.Resolved).request.skipAdAnalysis)
    }

    @Test fun analysisSkipIsDurableIdempotentAndWaitsForAcknowledgementWithoutChangingPositionOrPolicy() {
        for (recreate in listOf(false, true)) {
            val fixture = fixture()
            val ids = fixture.queue.submitPages(listOf(episode(1), episode(2)), FormatSelection.BestVideo,
                AdRemovalPolicy.WEIGHTED)
            val claim = assertNotNull(fixture.queue.claimNext())
            val request = genericRequest().copy(adRemovalPolicy = AdRemovalPolicy.WEIGHTED)
            fixture.queue.resolved(claim, request)
            assertFalse(fixture.queue.skipAdAnalysis(claim.id))
            fixture.queue.setProgress(claim, DownloadProgress(DownloadPhase.AD_ANALYSIS, detail = "OCR"))
            val position = fixture.queue.get(claim.id)!!.queuePosition
            assertTrue(fixture.queue.skipAdAnalysis(claim.id))
            assertTrue(fixture.queue.skipAdAnalysis(claim.id))
            assertTrue(fixture.queue.get(claim.id)!!.skipAdAnalysis)
            assertEquals(ItemInterruption.SKIP_AD_ANALYSIS, fixture.queue.get(claim.id)!!.interruption)
            val stopped = fixture.diskRecords()
            fixture.queue.setProgress(claim, DownloadProgress(DownloadPhase.DOWNLOADING))
            assertFalse(fixture.queue.complete(claim, "content://late", "video/mp4"))
            assertEquals(stopped, fixture.diskRecords())
            assertNull(fixture.queue.claimNext())
            val queue = if (recreate) fixture.reopen() else fixture.queue.also { it.acknowledgeStop(claim, true) }
            assertEquals(DownloadStatus.QUEUED, queue.get(claim.id)!!.status)
            assertNull(queue.get(claim.id)!!.progressPhase)
            assertNull(queue.pauseReason())
            assertEquals(position, queue.get(claim.id)!!.queuePosition)
            val resumed = assertNotNull(queue.claimNext())
            assertEquals(claim.id, resumed.id)
            assertTrue(resumed.skipAdAnalysis)
            assertEquals(request, resumed.resolvedRequest)
            assertEquals(AdRemovalPolicy.WEIGHTED, resumed.adRemovalPolicy)
            queue.fail(resumed, "source expired")
            assertTrue(queue.retry(claim.id))
            val retry = assertNotNull(DownloadQueue(fixture.store, fixture.workRoot).claimNext())
            assertTrue(retry.skipAdAnalysis)
            assertEquals(AdRemovalPolicy.WEIGHTED, retry.adRemovalPolicy)
            assertFalse(queue.get(ids.last())!!.skipAdAnalysis)
        }
    }

    @Test fun analysisSkipCannotWinAfterTransferCompletionOrAnotherInterruption() {
        for (phase in DownloadPhase.entries.filter { it != DownloadPhase.AD_ANALYSIS }) {
            val fixture = fixture()
            val id = fixture.queue.submit(genericRequest())
            val claim = assertNotNull(fixture.queue.claimNext())
            fixture.queue.setProgress(claim, DownloadProgress(phase, detail = "old analysis summary"))
            assertFalse(fixture.queue.skipAdAnalysis(id))
            assertFalse(fixture.queue.get(id)!!.skipAdAnalysis)
            fixture.queue.complete(claim, "content://done", "video/mp4")
            assertFalse(fixture.queue.skipAdAnalysis(id))
            assertNull(fixture.queue.get(id)!!.progressPhase)
        }
        val fixture = fixture()
        val id = fixture.queue.submit(genericRequest())
        val claim = assertNotNull(fixture.queue.claimNext())
        fixture.queue.setProgress(claim, DownloadProgress(DownloadPhase.AD_ANALYSIS))
        assertTrue(fixture.queue.pauseTask(id))
        assertFalse(fixture.queue.skipAdAnalysis(id))
        assertFalse(fixture.queue.get(id)!!.skipAdAnalysis)
    }

    @Test fun staleAnalysisClaimCannotReopenSkipAfterFullContentAttemptStarts() {
        val fixture = fixture()
        val id = fixture.queue.submit(genericRequest())
        val analysis = assertNotNull(fixture.queue.claimNext())
        fixture.queue.setProgress(analysis, DownloadProgress(DownloadPhase.AD_ANALYSIS))
        assertTrue(fixture.queue.skipAdAnalysis(id))
        fixture.queue.acknowledgeStop(analysis, interrupted = true)
        val full = assertNotNull(fixture.queue.claimNext())
        fixture.queue.setProgress(full, DownloadProgress(DownloadPhase.DOWNLOADING, .2f))
        val before = fixture.diskRecords()
        fixture.queue.setProgress(analysis, DownloadProgress(DownloadPhase.AD_ANALYSIS))
        assertFalse(fixture.queue.complete(analysis, "content://filtered", "video/mp4"))
        assertFalse(fixture.queue.skipAdAnalysis(id))
        assertEquals(before, fixture.diskRecords())
        assertTrue(fixture.queue.pauseTask(id))
        fixture.queue.acknowledgeStop(full, interrupted = true)
        assertEquals(DownloadStatus.PAUSED, fixture.queue.get(id)?.status)
        assertNull(fixture.queue.get(id)?.progressPhase)
        assertTrue(fixture.queue.get(id)!!.skipAdAnalysis)
    }

    @Test fun adPolicySnapshotSurvivesPauseRetryRestartAndDuplicateSubmission() {
        val fixture = fixture()
        val id = fixture.queue.submitPages(listOf(episode(1)), FormatSelection.BestVideo,
            AdRemovalPolicy.WEIGHTED).single()
        assertEquals(id, fixture.queue.submitPages(listOf(episode(1)), FormatSelection.BestVideo,
            AdRemovalPolicy.OFF).single())
        assertTrue(fixture.queue.pauseTask(id))
        val reopened = fixture.reopen()
        assertTrue(reopened.resumeTask(id))
        val first = assertNotNull(reopened.claimNext())
        assertEquals(AdRemovalPolicy.WEIGHTED, first.adRemovalPolicy)
        reopened.fail(first, "source failure")
        assertTrue(reopened.retry(id))
        assertEquals(AdRemovalPolicy.WEIGHTED, fixture.reopen().claimNext()?.adRemovalPolicy)
    }

    @Test fun legacyEmptyTaskGetsDefaultPersistedBeforeExecution() {
        val fixture = fixture()
        fixture.queue.submitPages(listOf(episode(1)), FormatSelection.BestVideo)
        fixture.store.transact { rows -> rows.map { it.copy(adRemovalPolicy = null, attempt = 2) } }
        val queue = fixture.reopen()
        assertEquals(AdRemovalPolicy.CONFIRMED_ONLY, fixture.diskRecords().single().adRemovalPolicy)
        assertEquals(AdRemovalPolicy.CONFIRMED_ONLY, queue.claimNext()?.adRemovalPolicy)
    }

    @Test fun legacyWorkIsPreservedAndCannotBypassMigrationWithResumePriorityOrRetry() {
        for (status in listOf(DownloadStatus.PAUSED, DownloadStatus.CANCELED, DownloadStatus.FAILED)) {
            val fixture = fixture()
            val id = fixture.queue.submitPages(listOf(episode(1)), FormatSelection.BestVideo).single()
            val partial = fixture.partialFile(id)
            fixture.store.transact { rows -> rows.map { it.copy(adRemovalPolicy = null,
                task = it.task.copy(status = status)) } }
            val queue = fixture.reopen()
            assertEquals(status, queue.get(id)?.status)
            assertTrue(queue.get(id)?.errorMessage.orEmpty().contains("已保留暫存"))
            assertTrue(queue.get(id)?.adPolicyMigrationRequired == true)
            assertFalse(queue.resumeTask(id))
            assertEquals(DownloadNowResult.REJECTED, queue.downloadNow(id))
            assertFalse(queue.retry(id))
            assertNull(queue.claimNext())
            assertEquals("resumable bytes", partial.readText())
            val fresh = queue.submitPages(listOf(episode(1)), FormatSelection.BestVideo, AdRemovalPolicy.OFF).single()
            assertNotEquals(id, fresh)
            assertEquals(fresh, queue.claimNext()?.id)
        }
    }

    private fun episode(number: Int) = EpisodePage(
        playbackUrl = "https://play.8maple.st/play/OD4C-2-$number.html",
        kind = SourceKind.EIGHT_MAPLE,
        originalUrl = "https://play.8maple.st/play/OD4C-2-$number.html?share=original",
        catalogUrl = "https://8maple.st/video/OD4C.html",
        lineId = "2",
        lineName = "播放線路 2",
        title = "劇集 第${number}集",
        seriesTitle = "劇集",
        seasonNumber = 3,
        episodeNumber = number,
        thumbnailUrl = "https://images.example/series.jpg",
    )

    private fun genericRequest(): DownloadRequest {
        val format = MediaFormat(
            formatId = "specific-1080",
            ext = "mp4",
            label = "1080p video and audio",
            height = 1080,
            hasVideo = true,
            hasAudio = true,
            isImage = false,
            filesizeBytes = 12345678L,
            resource = MediaResource("https://cdn.example/video.m3u8?token=retained", mapOf("Referer" to "https://media.example/watch/42", "X-Token" to "resource-token")),
        )
        return DownloadRequest(
            MediaItem(
                sourceUrl = "https://media.example/watch/42",
                playlistIndex = 2,
                id = "video-42",
                title = "Display title",
                thumbnailUrl = "https://media.example/thumbnail.jpg",
                durationSeconds = 120L,
                isImage = false,
                formats = listOf(format),
                source = SourceContext("https://media.example/share/42?ref=user", "https://media.example/watch/42"),
                downloadTarget = DownloadTarget.PAGE,
                httpHeaders = mapOf("User-Agent" to "test-agent", "Referer" to "https://media.example/"),
                originalTitle = "Original title",
                seriesTitle = "A series",
                seasonNumber = 2,
                episodeNumber = 10,
            ),
            FormatSelection.SpecificFormat(format),
        )
    }

    private fun fixture(): Fixture {
        val directory = temporary.newFolder()
        return Fixture(File(directory, "queue.json"), File(directory, "scratch"))
    }

    private class Fixture(val file: File, val workRoot: File) {
        val store = SerializedFileStore(file)
        val queue = DownloadQueue(store, workRoot)
        fun reopen() = DownloadQueue(SerializedFileStore(file), workRoot)
        fun diskRecords() = SerializedFileStore(file).read()
        fun partialFile(id: String): File = File(queue.workDirectory(id), "video.mp4.part").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("resumable bytes")
        }
    }

    private class CommitFailure : RuntimeException("Simulated queue commit failure")

    /** Test adapter for model/engine restart semantics; this does not exercise Android SQLite. */
    private class SerializedFileStore(private val file: File) : DownloadQueueStore {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        var failNextCommit = false
        var failNextRead = false
        var failOnTransaction: Int? = null
        var transactionCount = 0
            private set

        @Synchronized override fun read(): List<QueuedDownload> {
            if (failNextRead) {
                failNextRead = false
                throw CommitFailure()
            }
            return if (file.exists()) json.decodeFromString(file.readText()) else emptyList()
        }

        @Synchronized override fun transact(change: (List<QueuedDownload>) -> List<QueuedDownload>): List<QueuedDownload> {
            transactionCount++
            val updated = change(read()).toList()
            val encoded = json.encodeToString(updated)
            if (failNextCommit || failOnTransaction == transactionCount) {
                failNextCommit = false
                throw CommitFailure()
            }
            file.writeText(encoded)
            return read()
        }
    }
}
