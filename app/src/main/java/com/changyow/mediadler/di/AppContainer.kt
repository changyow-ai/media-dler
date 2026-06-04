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
import com.changyow.mediadler.data.RoutingMediaExtractor
import com.changyow.mediadler.data.threads.ThreadsExtractor
import com.changyow.mediadler.data.ytdlp.YtDlpMediaExtractor
import com.changyow.mediadler.download.DownloadQueue
import com.changyow.mediadler.download.PreviewStore
import com.changyow.mediadler.core.transcribe.TranscriptionEngine
import com.changyow.mediadler.data.transcribe.TranscriptStore
import com.changyow.mediadler.transcribe.LinkAudioResolver
import com.changyow.mediadler.transcribe.TranscriptionManager
import com.changyow.mediadler.transcribe.WhisperCppEngine
import com.changyow.mediadler.transcribe.WhisperModelManager

/** Lightweight manual dependency container — wiring is explicit, no annotation processing. */
class AppContainer(application: Application) {

    val engine = EngineInitializer(application)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(application)
    val historyStore = HistoryStore(application)
    val downloadQueue = DownloadQueue()
    val previewStore = PreviewStore(application)

    // On-device transcription (default engine). Cloud OpenRouter engine is added in milestone 3.
    val whisperModelManager = WhisperModelManager(application)
    val whisperCppEngine = WhisperCppEngine(application, whisperModelManager)
    val transcriptionEngine: TranscriptionEngine = whisperCppEngine

    // Resolves a shared link to captions (CC shortcut) or a downloaded audio file (milestone 2).
    val linkAudioResolver = LinkAudioResolver(application, engine)

    // Background transcription: persisted jobs, live state, queue, resume + cancel (milestone 2b).
    val transcriptStore = TranscriptStore(application)
    val transcriptionManager = TranscriptionManager(application, transcriptStore)

    val mediaExtractor: MediaExtractor = RoutingMediaExtractor(
        threads = ThreadsExtractor(),
        fallback = YtDlpMediaExtractor(engine),
    )

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
