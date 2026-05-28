package com.changyow.mediadler.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.AudioFormat
import com.changyow.mediadler.core.model.MediaKind
import com.changyow.mediadler.core.model.ShareMode
import com.changyow.mediadler.core.model.StorageMode
import com.changyow.mediadler.core.model.VideoQuality
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.util.enumOrDefault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    private object Keys {
        val shareMode = stringPreferencesKey("share_mode")
        val mediaKind = stringPreferencesKey("media_kind")
        val videoQuality = stringPreferencesKey("video_quality")
        val audioFormat = stringPreferencesKey("audio_format")
        val storageMode = stringPreferencesKey("storage_mode")
        val safTreeUri = stringPreferencesKey("saf_tree_uri")
        val downloadAllWhenMultiple = booleanPreferencesKey("download_all_when_multiple")
    }

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            shareMode = enumOrDefault(p[Keys.shareMode], ShareMode.ASK),
            defaultMediaKind = enumOrDefault(p[Keys.mediaKind], MediaKind.VIDEO),
            defaultVideoQuality = enumOrDefault(p[Keys.videoQuality], VideoQuality.BEST),
            audioFormat = enumOrDefault(p[Keys.audioFormat], AudioFormat.MP3),
            storageMode = enumOrDefault(p[Keys.storageMode], StorageMode.DOWNLOADS),
            safTreeUri = p[Keys.safTreeUri],
            downloadAllWhenMultiple = p[Keys.downloadAllWhenMultiple] ?: true,
        )
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings.first())
        context.settingsDataStore.edit { p ->
            p[Keys.shareMode] = next.shareMode.name
            p[Keys.mediaKind] = next.defaultMediaKind.name
            p[Keys.videoQuality] = next.defaultVideoQuality.name
            p[Keys.audioFormat] = next.audioFormat.name
            p[Keys.storageMode] = next.storageMode.name
            p[Keys.downloadAllWhenMultiple] = next.downloadAllWhenMultiple
            val saf = next.safTreeUri
            if (saf != null) p[Keys.safTreeUri] = saf else p.remove(Keys.safTreeUri)
        }
    }
}
