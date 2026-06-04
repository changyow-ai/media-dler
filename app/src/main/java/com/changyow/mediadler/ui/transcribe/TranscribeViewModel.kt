package com.changyow.mediadler.ui.transcribe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.transcribe.TranscriptJob
import com.changyow.mediadler.transcribe.TranscriptStatus
import com.changyow.mediadler.transcribe.TranscriptionManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Observes one transcription job from the manager and exposes cancel; the work runs in the service. */
class TranscribeViewModel(
    private val manager: TranscriptionManager,
    private val jobId: String,
) : ViewModel() {

    val job: StateFlow<TranscriptJob?> = manager.jobs
        .map { list -> list.firstOrNull { it.id == jobId } }
        .onEach { j ->
            // Mark the result as seen once the user is looking at the finished page.
            if (j?.status == TranscriptStatus.COMPLETED && !j.seen) manager.markSeen(jobId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manager.job(jobId))

    fun cancel() = manager.cancel(jobId)
}
