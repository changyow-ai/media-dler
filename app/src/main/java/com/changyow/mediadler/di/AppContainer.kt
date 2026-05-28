package com.changyow.mediadler.di

import android.app.Application
import com.changyow.mediadler.core.repo.Downloader
import com.changyow.mediadler.core.repo.MediaExtractor
import com.changyow.mediadler.core.repo.SettingsRepository
import com.changyow.mediadler.data.history.HistoryStore
import com.changyow.mediadler.data.settings.DataStoreSettingsRepository
import com.changyow.mediadler.data.storage.MediaStoreStorage
import com.changyow.mediadler.data.storage.SafStorage
import com.changyow.mediadler.data.ytdlp.EngineInitializer
import com.changyow.mediadler.data.ytdlp.YtDlpDownloader
import com.changyow.mediadler.data.ytdlp.YtDlpMediaExtractor
import com.changyow.mediadler.download.DownloadQueue
import com.changyow.mediadler.download.PreviewStore

/** Lightweight manual dependency container — wiring is explicit, no annotation processing. */
class AppContainer(application: Application) {

    val engine = EngineInitializer(application)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(application)
    val historyStore = HistoryStore(application)
    val downloadQueue = DownloadQueue()
    val previewStore = PreviewStore(application)

    val mediaExtractor: MediaExtractor = YtDlpMediaExtractor(engine)

    private val mediaStoreStorage = MediaStoreStorage(application)
    private val safStorage = SafStorage(application)

    val downloader: Downloader = YtDlpDownloader(
        context = application,
        engine = engine,
        settings = settingsRepository,
        mediaStore = mediaStoreStorage,
        saf = safStorage,
    )
}
