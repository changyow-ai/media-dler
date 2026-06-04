package com.changyow.mediadler.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
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
        val autoUpdateYtDlpOnStartup = booleanPreferencesKey("auto_update_ytdlp_on_startup")
    }

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.toAppSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        // Read-modify-write inside a single transaction so concurrent updates
        // can't clobber each other.
        context.settingsDataStore.edit { prefs ->
            prefs.write(transform(prefs.toAppSettings()))
        }
    }

    private fun Preferences.toAppSettings() = AppSettings(
        shareMode = enumOrDefault(this[Keys.shareMode], ShareMode.ASK),
        defaultMediaKind = enumOrDefault(this[Keys.mediaKind], MediaKind.VIDEO),
        defaultVideoQuality = enumOrDefault(this[Keys.videoQuality], VideoQuality.BEST),
        audioFormat = enumOrDefault(this[Keys.audioFormat], AudioFormat.MP3),
        storageMode = enumOrDefault(this[Keys.storageMode], StorageMode.DOWNLOADS),
        safTreeUri = this[Keys.safTreeUri],
        downloadAllWhenMultiple = this[Keys.downloadAllWhenMultiple] ?: true,
        autoUpdateYtDlpOnStartup = this[Keys.autoUpdateYtDlpOnStartup] ?: false,
    )

    private fun MutablePreferences.write(settings: AppSettings) {
        this[Keys.shareMode] = settings.shareMode.name
        this[Keys.mediaKind] = settings.defaultMediaKind.name
        this[Keys.videoQuality] = settings.defaultVideoQuality.name
        this[Keys.audioFormat] = settings.audioFormat.name
        this[Keys.storageMode] = settings.storageMode.name
        this[Keys.downloadAllWhenMultiple] = settings.downloadAllWhenMultiple
        this[Keys.autoUpdateYtDlpOnStartup] = settings.autoUpdateYtDlpOnStartup
        val saf = settings.safTreeUri
        if (saf != null) this[Keys.safTreeUri] = saf else remove(Keys.safTreeUri)
    }
}
