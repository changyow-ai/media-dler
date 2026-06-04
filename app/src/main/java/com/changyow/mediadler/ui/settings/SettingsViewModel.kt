package com.changyow.mediadler.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.data.ytdlp.EngineInitializer
import com.changyow.mediadler.data.ytdlp.EngineState
import com.changyow.mediadler.transcribe.TranscriptionManager
import com.changyow.mediadler.transcribe.WhisperModel
import com.changyow.mediadler.transcribe.WhisperModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val engine: EngineInitializer,
    private val models: WhisperModelManager,
    private val transcriptionManager: TranscriptionManager,
) : ViewModel() {

    /** UI state for the on-device whisper model the transcription engine uses. */
    sealed interface ModelState {
        data class Absent(val approxBytes: Long) : ModelState
        data class Downloading(val progress: Float) : ModelState
        data class Present(val bytes: Long) : ModelState
        data class Failed(val message: String) : ModelState
    }

    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _engineStatus = MutableStateFlow("yt-dlp …")
    val engineStatus: StateFlow<String> = _engineStatus.asStateFlow()

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    // The on-device model the download/delete controls act on, tracked from the model setting.
    private var selectedModel: WhisperModel = WhisperModel.of(AppSettings().transcribeModel)
    val selectedModelApproxBytes: Long get() = selectedModel.approxBytes

    private val _modelState = MutableStateFlow<ModelState>(modelStateOf(selectedModel))
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val state = engine.ensureInit()) {
                is EngineState.Failed -> _engineStatus.value = "引擎初始化失敗：${state.message}"
                else -> _engineStatus.value = engine.version()?.let { "yt-dlp $it" } ?: "yt-dlp 未就緒"
            }
        }
        // Follow the model setting: reflect the newly-chosen model's own download state. A download
        // still running for the previously-selected model keeps going but no longer drives this UI
        // (downloadModel's callbacks bail once selectedModel has moved on), so we must not leave the
        // new model's section showing the old model's "downloading" / size.
        viewModelScope.launch {
            repository.settings.collect { s ->
                val model = WhisperModel.of(s.transcribeModel)
                if (model != selectedModel) {
                    selectedModel = model
                    _modelState.value = modelStateOf(model)
                }
            }
        }
    }

    private fun modelStateOf(model: WhisperModel): ModelState =
        if (models.isDownloaded(model)) ModelState.Present(models.sizeBytes(model))
        else ModelState.Absent(model.approxBytes)

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

    fun downloadModel() {
        if (_modelState.value is ModelState.Downloading) return
        val model = selectedModel
        viewModelScope.launch {
            _modelState.value = ModelState.Downloading(0f)
            // Only this download drives the UI while its model is still the selected one; if the user
            // switches models mid-download, the state belongs to the new model, not this result.
            runCatching {
                models.ensure(model) { p ->
                    if (selectedModel == model) _modelState.value = ModelState.Downloading(p)
                }
            }.fold(
                onSuccess = {
                    if (selectedModel == model) _modelState.value = ModelState.Present(models.sizeBytes(model))
                },
                onFailure = {
                    if (selectedModel == model) _modelState.value = ModelState.Failed(it.message ?: "下載失敗")
                },
            )
        }
    }

    fun deleteModel() {
        if (_modelState.value is ModelState.Downloading) return
        val model = selectedModel
        models.delete(model)
        _modelState.value = ModelState.Absent(model.approxBytes)
    }

    /** Deletes transcription scratch files (downloaded audio, fetched subtitles, file copies). */
    fun clearTempFiles() {
        transcriptionManager.clearTempFiles()
    }
}
