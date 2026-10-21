package com.changyow.mediadler.ui.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.core.download.EpisodeSelectionPolicy
import com.changyow.mediadler.core.download.EpisodeSelectionPolicy.Selection
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.SeriesCatalog
import com.changyow.mediadler.core.model.VideoQuality
import com.changyow.mediadler.core.repo.SeriesCatalogExtractor
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.download.DownloadQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeriesPickerViewModel(
    private val url: String,
    private val extractor: SeriesCatalogExtractor,
    private val settings: SettingsRepository,
    private val queue: DownloadQueue,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Error(val message: String) : UiState
        data class Ready(
            val catalog: SeriesCatalog,
            val visibleLineId: String?,
            val selection: Selection,
            val quality: VideoQuality,
            val submitting: Boolean = false,
            val submitError: String? = null,
        ) : UiState
        data class Submitted(val count: Int) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load() }

    fun retry() {
        if (_state.value is UiState.Error) load()
    }

    private fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val current = settings.settings.first()
                val catalog = extractor.extractCatalog(url).getOrThrow()
                val visible = savedStateHandle.get<String>(VISIBLE_LINE)
                    ?.takeIf { id -> catalog.lines.any { it.id == id } } ?: catalog.lines.firstOrNull()?.id
                val selection = EpisodeSelectionPolicy.validate(catalog, Selection(
                    savedStateHandle[SELECTED_LINE], savedStateHandle.get<ArrayList<String>>(SELECTED_URLS)?.toSet().orEmpty()))
                val quality = savedStateHandle.get<String>(QUALITY)?.let { name ->
                    VideoQuality.entries.firstOrNull { it.name == name }
                } ?: current.defaultVideoQuality
                update(UiState.Ready(catalog, visible, selection, quality))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = UiState.Error(error.message ?: "無法讀取集數，請稍後重試")
            }
        }
    }

    fun showLine(lineId: String) = change { state ->
        if (state.catalog.lines.any { it.id == lineId }) state.copy(visibleLineId = lineId) else state
    }

    fun toggleEpisode(lineId: String, url: String) = change { state ->
        val line = state.catalog.lines.firstOrNull { it.id == lineId } ?: return@change state
        state.copy(selection = EpisodeSelectionPolicy.toggle(state.selection, line, url), submitError = null)
    }

    fun selectAll(lineId: String) = change { state ->
        val line = state.catalog.lines.firstOrNull { it.id == lineId } ?: return@change state
        state.copy(selection = EpisodeSelectionPolicy.selectAll(line), submitError = null)
    }

    fun clearLine(lineId: String) = change {
        it.copy(selection = EpisodeSelectionPolicy.clearLine(it.selection, lineId), submitError = null)
    }

    fun chooseQuality(quality: VideoQuality) = change { it.copy(quality = quality) }

    fun submit() {
        val ready = _state.value as? UiState.Ready ?: return
        if (ready.submitting) return
        val pages = EpisodeSelectionPolicy.orderedPages(ready.catalog, ready.selection)
        if (pages.isEmpty()) return
        // Set the guard synchronously, before launching IO, so rapid repeated taps cannot enqueue twice.
        update(ready.copy(submitting = true, submitError = null))
        viewModelScope.launch {
            try {
                val selection = ready.quality.maxHeight?.let { FormatSelection.CappedVideo(it) } ?: FormatSelection.BestVideo
                val policy = settings.settings.first().adRemovalPolicy
                val ids = withContext(Dispatchers.IO) { queue.submitPages(pages, selection, policy) }
                savedStateHandle[SELECTED_LINE] = null
                savedStateHandle[SELECTED_URLS] = arrayListOf<String>()
                _state.value = UiState.Submitted(ids.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                update(ready.copy(submitError = error.message ?: "無法保存排程，請重試"))
            }
        }
    }

    private fun change(transform: (UiState.Ready) -> UiState.Ready) {
        val ready = _state.value as? UiState.Ready ?: return
        if (!ready.submitting) update(transform(ready))
    }

    private fun update(ready: UiState.Ready) {
        savedStateHandle[VISIBLE_LINE] = ready.visibleLineId
        savedStateHandle[SELECTED_LINE] = ready.selection.lineId
        savedStateHandle[SELECTED_URLS] = ArrayList(ready.selection.urls)
        savedStateHandle[QUALITY] = ready.quality.name
        _state.value = ready
    }

    private companion object {
        const val VISIBLE_LINE = "series.visibleLine"
        const val SELECTED_LINE = "series.selectedLine"
        const val SELECTED_URLS = "series.selectedUrls"
        const val QUALITY = "series.quality"
    }
}
