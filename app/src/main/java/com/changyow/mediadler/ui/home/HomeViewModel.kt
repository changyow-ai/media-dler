package com.changyow.mediadler.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.isTerminal
import com.changyow.mediadler.data.history.HistoryStore
import com.changyow.mediadler.download.DownloadQueue
import com.changyow.mediadler.download.PreviewStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val queue: DownloadQueue,
    private val history: HistoryStore,
    private val previewStore: PreviewStore,
) : ViewModel() {

    /** Live in-memory tasks merged with persisted history (live wins on id collision). */
    val tasks: StateFlow<List<DownloadTask>> =
        combine(queue.tasks, history.history) { live, persisted ->
            (live + persisted.filter { p -> live.none { it.id == p.id } })
                .sortedByDescending { it.createdAt }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry(id: String): Boolean = queue.retry(id)

    fun remove(id: String) {
        tasks.value.firstOrNull { it.id == id }?.let { previewStore.delete(it.previewPath) }
        queue.remove(id)
        viewModelScope.launch { history.remove(id) }
    }

    fun clearFinished() {
        tasks.value.filter { it.status.isTerminal }.forEach { previewStore.delete(it.previewPath) }
        queue.clearFinished()
        viewModelScope.launch { history.clear() }
    }
}
