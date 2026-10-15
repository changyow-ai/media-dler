package com.changyow.mediadler.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.core.download.SelectionPlanner
import com.changyow.mediadler.core.extract.SpecialSources
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.model.ShareMode
import com.changyow.mediadler.core.repo.MediaExtractor
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.download.DownloadQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PickerViewModel(
    private val url: String,
    private val extractor: MediaExtractor,
    private val settings: SettingsRepository,
    private val queue: DownloadQueue,
    private val forcePicker: Boolean = false,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Error(val message: String) : UiState
        data class Picker(val items: List<MediaItem>) : UiState
        data class Submitted(val count: Int) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    private val submitLock = Any()
    private var submitting = false
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val current = settings.settings.first()
                val page = SpecialSources.episodePageOrNull(url)
                if (!forcePicker && page != null) {
                    val selection = current.defaultVideoQuality.maxHeight?.let { FormatSelection.CappedVideo(it) }
                        ?: FormatSelection.BestVideo
                    val ids = withContext(Dispatchers.IO) { queue.submitPages(listOf(page), selection) }
                    _state.value = UiState.Submitted(ids.size)
                    return@launch
                }
                val items = extractor.extract(url).getOrThrow()
                when {
                    items.isEmpty() -> _state.value = UiState.Error("找不到可下載的媒體")
                    !forcePicker && current.shareMode == ShareMode.ONE_TAP && (items.size == 1 || current.downloadAllWhenMultiple) -> {
                        val ids = withContext(Dispatchers.IO) {
                            queue.submitRequests(items.map { DownloadRequest(it, SelectionPlanner.defaultSelection(it, current)) })
                        }
                        _state.value = UiState.Submitted(ids.size)
                    }
                    else -> _state.value = UiState.Picker(items)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = UiState.Error(error.message ?: "解析或保存任務失敗")
            }
        }
    }

    fun submit(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        synchronized(submitLock) {
            if (submitting || _state.value !is UiState.Picker) return
            submitting = true
        }
        viewModelScope.launch {
            try {
                val ids = withContext(Dispatchers.IO) { queue.submitRequests(requests, allowPageDuplicates = forcePicker) }
                _state.value = UiState.Submitted(ids.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = UiState.Error(error.message ?: "無法保存下載任務")
            } finally {
                synchronized(submitLock) { submitting = false }
            }
        }
    }
}
