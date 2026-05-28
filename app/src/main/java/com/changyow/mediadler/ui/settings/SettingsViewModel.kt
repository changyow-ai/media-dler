package com.changyow.mediadler.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.data.ytdlp.EngineInitializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val engine: EngineInitializer,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _engineStatus = MutableStateFlow("yt-dlp …")
    val engineStatus: StateFlow<String> = _engineStatus.asStateFlow()

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    init {
        viewModelScope.launch {
            engine.ensureInit()
            _engineStatus.value = engine.version()?.let { "yt-dlp $it" } ?: "yt-dlp 未就緒"
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun updateEngine() {
        if (_updating.value) return
        viewModelScope.launch {
            _updating.value = true
            _engineStatus.value = "更新中…"
            engine.update().fold(
                onSuccess = { _engineStatus.value = it },
                onFailure = { _engineStatus.value = "更新失敗：${it.message ?: "未知錯誤"}" },
            )
            _updating.value = false
        }
    }
}
