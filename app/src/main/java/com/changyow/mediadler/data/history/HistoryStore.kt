package com.changyow.mediadler.data.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.DownloadTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.historyDataStore by preferencesDataStore(name = "history")

/** Persists terminal download tasks as a JSON list (simpler than a full database). */
class HistoryStore(private val context: Context) {

    private val key = stringPreferencesKey("records")
    private val json = Json { ignoreUnknownKeys = true }

    val history: Flow<List<DownloadTask>> = context.historyDataStore.data.map { p ->
        val raw = p[key] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<Record>>(raw) }
            .getOrDefault(emptyList())
            .map { it.toTask() }
    }

    suspend fun add(task: DownloadTask) {
        context.historyDataStore.edit { p ->
            val existing = p[key]?.let {
                runCatching { json.decodeFromString<List<Record>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val updated = (listOf(Record.from(task)) + existing.filter { it.id != task.id }).take(MAX)
            p[key] = json.encodeToString(updated)
        }
    }

    suspend fun clear() {
        context.historyDataStore.edit { it.remove(key) }
    }

    suspend fun remove(id: String) {
        context.historyDataStore.edit { p ->
            val existing = p[key]?.let {
                runCatching { json.decodeFromString<List<Record>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            p[key] = json.encodeToString(existing.filter { it.id != id })
        }
    }

    private companion object {
        const val MAX = 200
    }

    @Serializable
    private data class Record(
        val id: String,
        val title: String,
        val sourceUrl: String,
        val thumbnailUrl: String?,
        val formatLabel: String,
        val status: String,
        val outputUri: String?,
        val mimeType: String?,
        val errorMessage: String?,
        val createdAt: Long,
        val previewPath: String? = null,
    ) {
        fun toTask() = DownloadTask(
            id = id,
            title = title,
            sourceUrl = sourceUrl,
            thumbnailUrl = thumbnailUrl,
            formatLabel = formatLabel,
            status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.COMPLETED),
            progress = if (status == DownloadStatus.COMPLETED.name) 1f else 0f,
            outputUri = outputUri,
            mimeType = mimeType,
            errorMessage = errorMessage,
            createdAt = createdAt,
            previewPath = previewPath,
        )

        companion object {
            fun from(t: DownloadTask) = Record(
                id = t.id,
                title = t.title,
                sourceUrl = t.sourceUrl,
                thumbnailUrl = t.thumbnailUrl,
                formatLabel = t.formatLabel,
                status = t.status.name,
                outputUri = t.outputUri,
                mimeType = t.mimeType,
                errorMessage = t.errorMessage,
                createdAt = t.createdAt,
                previewPath = t.previewPath,
            )
        }
    }
}
