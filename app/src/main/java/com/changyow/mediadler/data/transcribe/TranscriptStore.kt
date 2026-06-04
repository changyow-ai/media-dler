package com.changyow.mediadler.data.transcribe

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.changyow.mediadler.transcribe.TranscriptJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.transcriptDataStore by preferencesDataStore(name = "transcripts")

/** Persists [TranscriptJob]s as a JSON list (mirrors the download HistoryStore approach). */
class TranscriptStore(private val context: Context) {

    private val key = stringPreferencesKey("jobs")
    private val json = Json { ignoreUnknownKeys = true }

    val jobs: Flow<List<TranscriptJob>> = context.transcriptDataStore.data.map { decode(it[key]) }

    suspend fun load(): List<TranscriptJob> = decode(context.transcriptDataStore.data.first()[key])

    /** Inserts or replaces [job] by id, newest first, capped at [MAX]. */
    suspend fun upsert(job: TranscriptJob) {
        context.transcriptDataStore.edit { p ->
            val existing = decode(p[key])
            val updated = (listOf(job) + existing.filter { it.id != job.id }).take(MAX)
            p[key] = json.encodeToString(updated)
        }
    }

    suspend fun remove(id: String) {
        context.transcriptDataStore.edit { p ->
            p[key] = json.encodeToString(decode(p[key]).filter { it.id != id })
        }
    }

    suspend fun clear() {
        context.transcriptDataStore.edit { it.remove(key) }
    }

    private fun decode(raw: String?): List<TranscriptJob> {
        raw ?: return emptyList()
        return runCatching { json.decodeFromString<List<TranscriptJob>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX = 200
    }
}
