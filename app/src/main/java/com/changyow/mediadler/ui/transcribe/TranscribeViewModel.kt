package com.changyow.mediadler.ui.transcribe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.core.transcribe.AudioRef
import com.changyow.mediadler.core.transcribe.TranscriptionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Runs the transcription for one shared audio source and exposes its progress/result. */
class TranscribeViewModel(
    private val engine: TranscriptionEngine,
    private val audio: AudioRef,
) : ViewModel() {

    sealed interface UiState {
        data class Running(val progress: Float) : UiState
        data class Done(val text: String, val language: String?) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Running(0f))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val transcript = engine.transcribe(audio) { p ->
                    _state.value = UiState.Running(p)
                }
                _state.value = UiState.Done(transcript.text, transcript.detectedLanguage)
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.message ?: "轉錄失敗")
            }
        }
    }
}
