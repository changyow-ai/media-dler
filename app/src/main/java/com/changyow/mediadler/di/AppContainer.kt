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
import com.changyow.mediadler.core.model.AppSettings
import com.changyow.mediadler.core.model.OnDeviceBackend
import com.changyow.mediadler.core.model.TranscribeEngine
import com.changyow.mediadler.data.transcribe.TranscriptStore
import com.changyow.mediadler.transcribe.CloudTranscriptionEngine
import com.changyow.mediadler.transcribe.LinkAudioResolver
import com.changyow.mediadler.transcribe.SherpaModelManager
import com.changyow.mediadler.transcribe.SherpaOnnxEngine
import com.changyow.mediadler.transcribe.StreamingEngine
import com.changyow.mediadler.transcribe.TranscriptionManager
import com.changyow.mediadler.transcribe.WhisperCppEngine
import com.changyow.mediadler.transcribe.WhisperModelManager

/** Lightweight manual dependency container — wiring is explicit, no annotation processing. */
class AppContainer(application: Application, isAppForeground: () -> Boolean = { false }) {

    val engine = EngineInitializer(application)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(application)
    val historyStore = HistoryStore(application)
    val downloadQueue = DownloadQueue()
    val previewStore = PreviewStore(application)

    // Transcription engines, all implementing StreamingEngine: on-device whisper.cpp (ggml) and
    // sherpa-onnx (ONNX: SenseVoice/Paraformer/Qwen3), plus a cloud OpenRouter backend (opt-in,
    // user-supplied key). The service picks per the transcribeEngine + (for on-device) model backend.
    val whisperModelManager = WhisperModelManager(application)
    val sherpaModelManager = SherpaModelManager(application)
    val whisperCppEngine = WhisperCppEngine(application, whisperModelManager, settingsRepository)
    val sherpaOnnxEngine = SherpaOnnxEngine(application, sherpaModelManager, settingsRepository)
    val cloudTranscriptionEngine = CloudTranscriptionEngine(application, settingsRepository)

    /** On-device routes to whisper.cpp or sherpa-onnx by the chosen model's backend; cloud is cloud. */
    fun streamingEngine(settings: AppSettings): StreamingEngine = when (settings.transcribeEngine) {
        TranscribeEngine.CLOUD -> cloudTranscriptionEngine
        TranscribeEngine.ON_DEVICE -> when (settings.transcribeModel.backend) {
            OnDeviceBackend.WHISPER_CPP -> whisperCppEngine
            OnDeviceBackend.SHERPA -> sherpaOnnxEngine
        }
    }

    val mediaExtractor: MediaExtractor = RoutingMediaExtractor(
        threads = ThreadsExtractor(),
        fallback = YtDlpMediaExtractor(engine),
    )

    // Resolves a shared link to captions (CC shortcut) or a downloaded audio file (milestone 2).
    // Threads links are routed through mediaExtractor first (yt-dlp can't extract Threads).
    val linkAudioResolver = LinkAudioResolver(application, engine, mediaExtractor)

    // Background transcription: persisted jobs, live state, queue, resume + cancel (milestone 2b).
    val transcriptStore = TranscriptStore(application)
    val transcriptionManager = TranscriptionManager(application, transcriptStore, isAppForeground)

    val mediaStoreStorage = MediaStoreStorage(application)
    val safStorage = SafStorage(application)

    val downloader: Downloader = YtDlpDownloader(
        context = application,
        engine = engine,
        settings = settingsRepository,
        mediaStore = mediaStoreStorage,
        saf = safStorage,
    )
}
