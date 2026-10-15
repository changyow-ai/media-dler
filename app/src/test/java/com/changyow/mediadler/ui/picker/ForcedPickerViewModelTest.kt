package com.changyow.mediadler.ui.picker

import androidx.lifecycle.ViewModelStore
import com.changyow.mediadler.core.model.*
import com.changyow.mediadler.core.repo.*
import com.changyow.mediadler.download.DownloadQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ForcedPickerViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun clear() { viewModels.clear(); Dispatchers.resetMain() }

    @Test fun defaultSpecialPageQueuesWithoutExtractionRegardlessOfAskSetting() = runTest(dispatcher) {
        for (url in listOf("https://play.8maple.st/play/OD4C-2-4.html", "https://gimytv.io/eps/12345-4.html")) {
            val fixture = fixture(url, AppSettings(shareMode = ShareMode.ASK), force = false)
            assertIs<PickerViewModel.UiState.Submitted>(fixture.viewModel.state.first { it !is PickerViewModel.UiState.Loading })
            assertTrue(fixture.extractor.calls.isEmpty())
            assertEquals(1, fixture.store.read().size)
            assertIs<QueuedSpec.Page>(fixture.store.read().single().spec)
        }
    }

    @Test fun forcedSpecialPickerExtractsAndPresentsMediaWithoutAutoSubmitting() = runTest(dispatcher) {
        val fixture = fixture("https://play.8maple.st/play/OD4C-2-4.html", AppSettings(shareMode = ShareMode.ONE_TAP), force = true)
        val picker = assertIs<PickerViewModel.UiState.Picker>(fixture.viewModel.state.first { it !is PickerViewModel.UiState.Loading })
        assertEquals(1, picker.items.size)
        assertEquals(1, fixture.extractor.calls.size)
        assertTrue(fixture.store.read().isEmpty())
        fixture.viewModel.submit(listOf(DownloadRequest(picker.items.single(), FormatSelection.Audio(AudioFormat.M4A))))
        assertIs<PickerViewModel.UiState.Submitted>(fixture.viewModel.state.first { it is PickerViewModel.UiState.Submitted })
        assertEquals(FormatSelection.Audio(AudioFormat.M4A), (fixture.store.read().single().spec as QueuedSpec.Page).selection)
    }

    @Test fun forcedGeneralPickerOverridesOneTapWhileDefaultGeneralKeepsUserSetting() = runTest(dispatcher) {
        val url = "https://media.example/video"
        val settings = AppSettings(shareMode = ShareMode.ONE_TAP)
        val forced = fixture(url, settings, force = true)
        assertIs<PickerViewModel.UiState.Picker>(forced.viewModel.state.first { it !is PickerViewModel.UiState.Loading })
        assertTrue(forced.store.read().isEmpty())
        val automatic = fixture(url, settings, force = false)
        assertIs<PickerViewModel.UiState.Submitted>(automatic.viewModel.state.first { it !is PickerViewModel.UiState.Loading })
        assertIs<QueuedSpec.Resolved>(automatic.store.read().single().spec)
        val ask = fixture(url, AppSettings(shareMode = ShareMode.ASK), force = false)
        assertIs<PickerViewModel.UiState.Picker>(ask.viewModel.state.first { it !is PickerViewModel.UiState.Loading })
        assertTrue(ask.store.read().isEmpty())
    }

    @Test fun manualAudioCreatesNewTaskBesideActiveVideoAndRapidDoubleTapSubmitsOnlyOnce() = runTest(dispatcher) {
        val url = "https://play.8maple.st/play/OD4C-2-4.html"
        val automatic = fixture(url, AppSettings(), force = false)
        automatic.viewModel.state.first { it is PickerViewModel.UiState.Submitted }
        val videoId = automatic.store.read().single().task.id
        automatic.queue.claimNext()
        val manual = fixture(url, AppSettings(), force = true, existing = automatic)
        val picker = assertIs<PickerViewModel.UiState.Picker>(manual.viewModel.state.first { it !is PickerViewModel.UiState.Loading })
        val audio = listOf(DownloadRequest(picker.items.single(), FormatSelection.Audio(AudioFormat.M4A)))
        manual.viewModel.submit(audio)
        manual.viewModel.submit(audio)
        manual.viewModel.state.first { it is PickerViewModel.UiState.Submitted }
        val records = manual.store.read()
        assertEquals(2, records.size)
        assertEquals(videoId, records.first().task.id)
        assertEquals(DownloadStatus.EXTRACTING, records.first().task.status)
        assertEquals(FormatSelection.Audio(AudioFormat.M4A), (records.last().spec as QueuedSpec.Page).selection)
        assertEquals(DownloadStatus.QUEUED, records.last().task.status)
    }

    @Test fun defaultRepeatedSpecialShareStillReturnsExistingActiveTaskWithoutDuplicate() = runTest(dispatcher) {
        val url = "https://play.8maple.st/play/OD4C-2-4.html"
        val original = fixture(url, AppSettings(), force = false)
        original.viewModel.state.first { it is PickerViewModel.UiState.Submitted }
        original.queue.claimNext()
        val before = original.store.read()
        val repeated = fixture(url, AppSettings(), force = false, existing = original)
        repeated.viewModel.state.first { it is PickerViewModel.UiState.Submitted }
        assertEquals(before, repeated.store.read())
        assertTrue(repeated.extractor.calls.isEmpty())
    }

    private fun fixture(url: String, settings: AppSettings, force: Boolean, existing: Fixture? = null): Fixture {
        val store = existing?.store ?: Store()
        val queue = existing?.queue ?: DownloadQueue(store, temporary.newFolder())
        val extractor = Extractor()
        val initialSettings = settings
        val repository = object : SettingsRepository {
            override val settings = MutableStateFlow(initialSettings)
            override suspend fun update(transform: (AppSettings) -> AppSettings) { this.settings.value = transform(this.settings.value) }
        }
        val viewModel = PickerViewModel(url, extractor, repository, queue, force)
        viewModels.put("${url}_${force}_${viewModels.hashCode()}_${System.nanoTime()}", viewModel)
        return Fixture(store, queue, extractor, viewModel)
    }

    private data class Fixture(val store: Store, val queue: DownloadQueue, val extractor: Extractor, val viewModel: PickerViewModel)
    private class Store : DownloadQueueStore {
        private var records = emptyList<QueuedDownload>()
        @Synchronized override fun read() = records.toList()
        @Synchronized override fun transact(change: (List<QueuedDownload>) -> List<QueuedDownload>): List<QueuedDownload> {
            records = change(read()).toList()
            return read()
        }
    }
    private class Extractor : MediaExtractor {
        val calls = mutableListOf<String>()
        override suspend fun extract(url: String): Result<List<MediaItem>> {
            calls += url
            val kind = if (url.contains("8maple")) SourceKind.EIGHT_MAPLE else SourceKind.GENERAL
            return Result.success(listOf(MediaItem("https://cdn.example/video.m3u8", null, "video", "Video", null,
                null, false, emptyList(), SourceContext(url, url, kind))))
        }
    }
}
