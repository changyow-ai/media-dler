package com.changyow.mediadler.ui.series

import androidx.lifecycle.SavedStateHandle
import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.CatalogEpisode
import com.changyow.mediadler.core.model.EpisodePage
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.PlaybackLine
import com.changyow.mediadler.core.model.QueuedDownload
import com.changyow.mediadler.core.model.QueuedSpec
import com.changyow.mediadler.core.model.SeriesCatalog
import com.changyow.mediadler.core.model.SourceKind
import com.changyow.mediadler.core.model.VideoQuality
import com.changyow.mediadler.core.repo.DownloadQueueStore
import com.changyow.mediadler.core.repo.SeriesCatalogExtractor
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.download.DownloadQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesPickerViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }
    private fun episode(line: Int, number: Int) = CatalogEpisode("第${number}集", EpisodePage(
        "https://play.8maple.st/play/series-$line-$number.html", SourceKind.EIGHT_MAPLE,
        title = "劇名 第${number}集", episodeNumber = number), number)
    private val a = PlaybackLine("A", "速通線路", 0, listOf(episode(1, 10), episode(1, 2), episode(1, 1)))
    private val b = PlaybackLine("B", "愛看線路", 1, listOf(episode(2, 1), episode(2, 2)))
    private val catalog = SeriesCatalog("share", "https://8maple.st/video/show.html", SourceKind.EIGHT_MAPLE,
        "劇名", null, listOf(a, b))
    private val settings = object : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings(defaultVideoQuality = VideoQuality.P720))
        override suspend fun update(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
    }

    @Test fun catalogNeverAutoqueuesAndSwipingPreservesAUntilSelectingB() = runBlocking {
        val store = Store()
        val queue = queue(store)
        var calls = 0
        val model = model(queue, extractor = { calls++; Result.success(catalog) })
        ready(model)
        assertTrue(queue.tasks.value.isEmpty())
        assertEquals(1, calls)
        model.toggleEpisode(a.id, a.episodes[0].page.playbackUrl)
        model.toggleEpisode(a.id, a.episodes[1].page.playbackUrl)
        model.showLine(b.id)
        val swiped = ready(model)
        assertEquals(b.id, swiped.visibleLineId)
        assertEquals(a.id, swiped.selection.lineId)
        assertEquals(2, swiped.selection.urls.size)
        model.clearLine(b.id)
        assertEquals(swiped.selection, ready(model).selection)
        model.toggleEpisode(b.id, b.episodes[0].page.playbackUrl)
        assertEquals(setOf(b.episodes[0].page.playbackUrl), ready(model).selection.urls)
        assertEquals(b.id, ready(model).selection.lineId)
        assertTrue(queue.tasks.value.isEmpty())
    }

    @Test fun submitCommitsOneNaturallyOrderedUnresolvedBatchWithActualLineAndQuality() = runBlocking {
        val store = Store()
        val queue = queue(store)
        val handle = SavedStateHandle()
        val model = model(queue, handle)
        ready(model)
        val before = store.calls
        a.episodes.forEach { model.toggleEpisode(a.id, it.page.playbackUrl) }
        model.submit()
        assertEquals(3, submitted(model).count)
        model.submit()
        assertEquals(before + 1, store.calls)
        val specs = store.records.map { assertIs<QueuedSpec.Page>(it.spec) }
        assertEquals(listOf(1, 2, 10), specs.map { it.page.episodeNumber })
        assertTrue(specs.all { it.page.lineId == a.id && it.page.lineName == a.name && it.page.catalogUrl == catalog.pageUrl })
        assertTrue(specs.all { it.selection == FormatSelection.CappedVideo(720) })
        assertTrue(store.records.all { it.resolvedRequest == null })
        assertTrue(handle.get<ArrayList<String>>("series.selectedUrls").isNullOrEmpty())
    }

    @Test fun emptyAndInFlightRepeatedSubmitAreGuardedAndExistingQueueIsUntouchedByPickerEdits() = runBlocking {
        val store = Store()
        val queue = queue(store)
        queue.submitPages(listOf(b.episodes[1].page), FormatSelection.BestVideo)
        val original = store.records.single()
        val model = model(queue)
        ready(model)
        val before = store.calls
        model.submit()
        assertEquals(before, store.calls)
        model.selectAll(a.id)
        assertEquals(original, store.records.single())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.entered = entered
        store.release = release
        try {
            model.submit()
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertTrue(ready(model).submitting)
            model.submit()
            model.selectAll(b.id)
            assertEquals(a.id, ready(model).selection.lineId)
            assertEquals(before + 1, store.calls)
        } finally { release.countDown() }
        submitted(model)
        assertEquals(original, store.records.first())
        assertEquals(4, store.records.size)
    }

    @Test fun queueFailureKeepsSelectionAndQualityForRetryThenClearsOnlyAfterSuccess() = runBlocking {
        val store = Store()
        val queue = queue(store)
        val model = model(queue)
        ready(model)
        model.selectAll(a.id)
        model.chooseQuality(VideoQuality.P1080)
        store.fail = true
        model.submit()
        val failed = withTimeout(5_000) { model.state.first {
            it is SeriesPickerViewModel.UiState.Ready && it.submitError != null
        } } as SeriesPickerViewModel.UiState.Ready
        assertFalse(failed.submitting)
        assertEquals(a.id, failed.selection.lineId)
        assertEquals(3, failed.selection.urls.size)
        assertEquals(VideoQuality.P1080, failed.quality)
        assertTrue(store.records.isEmpty())
        store.fail = false
        model.submit()
        assertEquals(3, submitted(model).count)
    }

    @Test fun savedSelectionVisibleLineAndQualityRestoreAndRemovedChoicesAreDropped() = runBlocking {
        val queue = queue(Store())
        val handle = SavedStateHandle()
        val original = model(queue, handle)
        ready(original)
        original.toggleEpisode(a.id, a.episodes[0].page.playbackUrl)
        original.toggleEpisode(a.id, a.episodes[1].page.playbackUrl)
        original.showLine(b.id)
        original.chooseQuality(VideoQuality.P480)
        val restored = ready(model(queue, handle))
        assertEquals(b.id, restored.visibleLineId)
        assertEquals(a.id, restored.selection.lineId)
        assertEquals(2, restored.selection.urls.size)
        assertEquals(VideoQuality.P480, restored.quality)
        val changed = catalog.copy(lines = listOf(a.copy(episodes = a.episodes.drop(1))))
        val refreshed = ready(model(queue, handle) { Result.success(changed) })
        assertEquals(a.id, refreshed.visibleLineId)
        assertEquals(setOf(a.episodes[1].page.playbackUrl), refreshed.selection.urls)
    }

    @Test fun catalogFailureRetriesAndEmptyCatalogCannotSubmit() = runBlocking {
        val store = Store()
        var calls = 0
        val model = model(queue(store), extractor = {
            calls++
            if (calls == 1) Result.failure(IllegalStateException("讀取失敗"))
            else Result.success(catalog.copy(lines = emptyList()))
        })
        assertIs<SeriesPickerViewModel.UiState.Error>(withTimeout(5_000) { model.state.first { it is SeriesPickerViewModel.UiState.Error } })
        model.retry()
        val empty = ready(model)
        assertTrue(empty.catalog.lines.isEmpty())
        assertNull(empty.visibleLineId)
        val before = store.calls
        model.submit()
        assertEquals(before, store.calls)
        assertEquals(2, calls)
    }

    private fun queue(store: Store) = DownloadQueue(store, temporary.newFolder())
    private fun model(queue: DownloadQueue, handle: SavedStateHandle = SavedStateHandle(),
        extractor: suspend () -> Result<SeriesCatalog> = { Result.success(catalog) }) =
        SeriesPickerViewModel(catalog.pageUrl, object : SeriesCatalogExtractor {
            override suspend fun extractCatalog(url: String) = extractor()
        }, settings, queue, handle)
    private suspend fun ready(model: SeriesPickerViewModel) = withTimeout(5_000) {
        model.state.first { it is SeriesPickerViewModel.UiState.Ready } as SeriesPickerViewModel.UiState.Ready
    }
    private suspend fun submitted(model: SeriesPickerViewModel) = withTimeout(5_000) {
        model.state.first { it is SeriesPickerViewModel.UiState.Submitted } as SeriesPickerViewModel.UiState.Submitted
    }
    private class Store : DownloadQueueStore {
        @Volatile var records = emptyList<QueuedDownload>()
        @Volatile var calls = 0
        @Volatile var fail = false
        var entered: CountDownLatch? = null
        var release: CountDownLatch? = null
        override fun read() = records
        override fun transact(change: (List<QueuedDownload>) -> List<QueuedDownload>): List<QueuedDownload> {
            calls++
            entered?.countDown()
            check(release?.await(5, TimeUnit.SECONDS) != false) { "測試提交未釋放" }
            if (fail) error("無法保存任務")
            records = change(records)
            return records
        }
    }
}
