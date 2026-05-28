package com.changyow.mediadler.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.data.history.HistoryStore
import com.changyow.mediadler.download.DownloadQueue
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val queue: DownloadQueue,
    private val history: HistoryStore,
) : ViewModel() {

    /** Live in-memory tasks merged with persisted history (live wins on id collision). */
    val tasks: StateFlow<List<DownloadTask>> =
        combine(queue.tasks, history.history) { live, persisted ->
            (live + persisted.filter { p -> live.none { it.id == p.id } })
                .sortedByDescending { it.createdAt }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry(id: String): Boolean = queue.retry(id)

    fun remove(id: String) = queue.remove(id)

    fun clearFinished() {
        queue.clearFinished()
        viewModelScope.launch { history.clear() }
    }
}
