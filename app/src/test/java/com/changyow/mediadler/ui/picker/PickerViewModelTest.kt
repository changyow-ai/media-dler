package com.changyow.mediadler.ui.picker

import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.QueuedDownload
import com.changyow.mediadler.core.model.QueuedSpec
import com.changyow.mediadler.core.model.ShareMode
import com.changyow.mediadler.core.repo.DownloadQueueStore
import com.changyow.mediadler.core.repo.MediaExtractor
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.download.DownloadQueue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PickerViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }
    private val items = listOf("A", "B").map { MediaItem("https://example.com/$it", null, it, it, null, null, false, emptyList()) }

    @Test fun oneTapCommitsTheWholeBatchBeforeReportingSubmitted() = runBlocking {
        val store = Store()
        val queue = DownloadQueue(store, File(temporary.newFolder(), "work"))
        val initialTransactions = store.transactions
        val vm = model(queue, ShareMode.ONE_TAP)
        assertEquals(PickerViewModel.UiState.Submitted(2), withTimeout(5_000) { vm.state.first { it is PickerViewModel.UiState.Submitted } })
        assertEquals(initialTransactions + 1, store.transactions)
        assertEquals(listOf("A", "B"), queue.tasks.value.map { it.title })
    }

    @Test fun oneTapCommitFailureIsVisibleWithoutSavingAPartialBatch() = runBlocking {
        val store = Store()
        val queue = DownloadQueue(store, temporary.newFolder())
        store.fail = true
        val vm = model(queue, ShareMode.ONE_TAP)
        val error = withTimeout(5_000) { vm.state.first { it is PickerViewModel.UiState.Error } }
        assertEquals("無法保存任務", assertIs<PickerViewModel.UiState.Error>(error).message)
        assertTrue(queue.tasks.value.isEmpty())
        assertTrue(store.records.isEmpty())
    }

    @Test fun manualSubmissionCommitFailureUsesTheSameVisibleError() = runBlocking {
        val store = Store()
        val queue = DownloadQueue(store, temporary.newFolder())
        val vm = model(queue, ShareMode.ASK)
        withTimeout(5_000) { vm.state.first { it is PickerViewModel.UiState.Picker } }
        store.fail = true
        vm.submit(items.map { DownloadRequest(it, FormatSelection.BestVideo) })
        assertIs<PickerViewModel.UiState.Error>(withTimeout(5_000) { vm.state.first { it is PickerViewModel.UiState.Error } })
        assertTrue(store.records.isEmpty())
        assertTrue(queue.tasks.value.isEmpty())
    }

    @Test fun specialEpisodeLandsUnresolvedWithoutCallingTheExtractor() = runBlocking {
        val store = Store()
        val queue = DownloadQueue(store, temporary.newFolder())
        val page = "https://play.8maple.st/play/test-2-4.html"
        val vm = PickerViewModel(page, object : MediaExtractor {
            override suspend fun extract(url: String): Result<List<MediaItem>> = error("入列時不能解析串流")
        }, settings(ShareMode.ASK), queue)
        assertEquals(PickerViewModel.UiState.Submitted(1), withTimeout(5_000) { vm.state.first { it is PickerViewModel.UiState.Submitted } })
        assertEquals(page, assertIs<QueuedSpec.Page>(store.records.single().spec).page.playbackUrl)
        assertEquals(null, store.records.single().resolvedRequest)
    }

    private fun model(queue: DownloadQueue, mode: ShareMode) = PickerViewModel("https://example.com/post", object : MediaExtractor {
        override suspend fun extract(url: String) = Result.success(items)
    }, settings(mode), queue)

    private fun settings(mode: ShareMode) = object : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings(shareMode = mode))
        override suspend fun update(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
    }

    private class Store : DownloadQueueStore {
        var records = emptyList<QueuedDownload>()
        var transactions = 0
        var fail = false
        override fun read() = records
        override fun transact(change: (List<QueuedDownload>) -> List<QueuedDownload>): List<QueuedDownload> {
            val updated = change(records)
            if (fail) error("無法保存任務")
            transactions++
            records = updated
            return records
        }
    }
}
