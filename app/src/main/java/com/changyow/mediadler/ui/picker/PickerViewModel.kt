package com.changyow.mediadler.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.core.download.SelectionPlanner
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

class PickerViewModel(
    private val url: String,
    private val extractor: MediaExtractor,
    private val settings: SettingsRepository,
    private val queue: DownloadQueue,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Error(val message: String) : UiState
        data class Picker(val items: List<MediaItem>) : UiState
        data class Submitted(val count: Int) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val current = settings.settings.first()
            extractor.extract(url).fold(
                onSuccess = { items ->
                    when {
                        items.isEmpty() -> _state.value = UiState.Error("找不到可下載的媒體")
                        current.shareMode == ShareMode.ONE_TAP &&
                            (items.size == 1 || current.downloadAllWhenMultiple) -> {
                            items.forEach {
                                queue.submit(DownloadRequest(it, SelectionPlanner.defaultSelection(it, current)))
                            }
                            _state.value = UiState.Submitted(items.size)
                        }
                        else -> _state.value = UiState.Picker(items)
                    }
                },
                onFailure = { _state.value = UiState.Error(it.message ?: "解析失敗") },
            )
        }
    }

    fun submit(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        requests.forEach { queue.submit(it) }
        _state.value = UiState.Submitted(requests.size)
    }
}
