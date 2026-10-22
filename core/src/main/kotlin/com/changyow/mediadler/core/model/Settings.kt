package com.changyow.mediadler.core.model

enum class ShareMode { ONE_TAP, ASK }

enum class MediaKind { VIDEO, AUDIO }

enum class VideoQuality(val maxHeight: Int?) {
    BEST(null), P1080(1080), P720(720), P480(480), P360(360)
}

enum class AudioFormat(val ext: String) { MP3("mp3"), M4A("m4a") }

enum class StorageMode { DOWNLOADS, SAF }

/**
 * Forced transcription language. whisper takes a single language param, so this sets the *primary*
 * language; secondary-language words (e.g. English in Mandarin speech) still pass through because the
 * model is multilingual. [AUTO] keeps whisper's per-clip auto-detection (can misfire on short/noisy
 * intros). [code] is the whisper/ISO code, null for auto.
 */
enum class TranscribeLanguage(val code: String?, val label: String) {
    AUTO(null, "自動偵測"),
    ZH("zh", "中文"),
    EN("en", "English"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    ES("es", "Español"),
    FR("fr", "Français"),
    DE("de", "Deutsch"),
}

/** Which transcription backend runs. On-device is the offline default; cloud is opt-in (see below). */
enum class TranscribeEngine { ON_DEVICE, CLOUD }

/**
 * Which on-device inference runtime backs a [TranscribeModel]. whisper.cpp runs ggml models via JNI;
 * sherpa-onnx runs supported ONNX models via ONNX Runtime. The UI never exposes
 * this — the chosen model implies its backend (see [AppContainer.streamingEngine]).
 */
enum class OnDeviceBackend { WHISPER_CPP, SHERPA }

enum class ModelTier(val label: String) {
    COMPARISON("建議比較"), EXPERIMENTAL("實驗"), LEGACY("舊版／特定用途")
}

/**
 * Runnable deployments, not a ranking of model-family benchmark scores. Keep persisted enum names
 * and old downloads usable; new candidates never replace the user's selection or the default.
 */
enum class TranscribeModel(
    val label: String,
    val backend: OnDeviceBackend,
    val tier: ModelTier,
    val hint: String,
    val sourceUrl: String,
) {
    SENSE_VOICE("SenseVoice Small INT8（保留預設）", OnDeviceBackend.SHERPA, ModelTier.COMPARISON,
        "沿用目前的 2024-07-17 模型、標點與語言設定，作為比較基準；不自動換模型。權重：FunASR Model License。",
        "https://huggingface.co/FunAudioLLM/SenseVoiceSmall"),
    FIRE_RED_ASR2("FireRedASR2 AED INT8（中英候選）", OnDeviceBackend.SHERPA, ModelTier.COMPARISON,
        "優先比較中文、英文與中英夾雜；約 839 MB 下載，解壓約 1.24 GB。AED 自動辨識、不接受語言鎖定，未含標點模型。原生輸出有長度上限，快語速與尾字須實測。權重：Apache-2.0。",
        "https://huggingface.co/FireRedTeam/FireRedASR2-AED"),
    BREEZE_25_Q5("Breeze-ASR-25 Q5（台灣華語／中英）", OnDeviceBackend.WHISPER_CPP, ModelTier.COMPARISON,
        "台灣華語與中英混用的專項候選，約 1.08 GB；不是台語用途的 Breeze-ASR-26。MediaTek 原模型、shdennlin GGML 轉換。量化版尚未在本手機證明勝過 SenseVoice。權重：Apache-2.0。",
        "https://huggingface.co/MediaTek-Research/Breeze-ASR-25"),
    LARGE_V3_Q5("Whisper large-v3 Q5（完整大型對照）", OnDeviceBackend.WHISPER_CPP, ModelTier.COMPARISON,
        "完整 large-v3 的 Q5 量化版，約 1.08 GB；不是精簡 decoder 的 turbo。成熟多語對照組，通常較耗時，不保證每段都比較準。權重：MIT。",
        "https://huggingface.co/openai/whisper-large-v3"),
    TURBO_Q5("Whisper large-v3-turbo Q5（速度折衷）", OnDeviceBackend.WHISPER_CPP, ModelTier.COMPARISON,
        "保留既有約 574 MB 量化版；decoder 較少，作為速度／品質折衷，不能把非量化或其他硬體的成績直接套用。權重：MIT。",
        "https://huggingface.co/openai/whisper-large-v3-turbo"),
    FUNASR_NANO("Fun-ASR-Nano-2512 INT8", OnDeviceBackend.SHERPA, ModelTier.EXPERIMENTAL,
        "中英日與方言的實驗候選；約 842 MB 壓縮下載，不是小型手機模型。短分段仍需檢查快語速與尾字。自動模式未回傳語言；需穩定轉繁體時請指定中文。權重：Apache-2.0。",
        "https://huggingface.co/FunAudioLLM/Fun-ASR-Nano-2512"),
    QWEN3("Qwen3-ASR 0.6B INT8", OnDeviceBackend.SHERPA, ModelTier.EXPERIMENTAL,
        "保留已接線的 0.6B ASR 模型，約 879 MB 壓縮下載；不是泛用 Qwen 的版本排名。短分段降低 512-token KV 截斷風險。自動模式未回傳語言；轉繁體請指定中文。權重：Apache-2.0。",
        "https://huggingface.co/Qwen/Qwen3-ASR-0.6B"),
    BASE("Whisper base（舊版）", OnDeviceBackend.WHISPER_CPP, ModelTier.LEGACY,
        "保留既有選擇與下載管理，約 148 MB；本次不以小體積作為中英準確率的推薦依據。權重：MIT。",
        "https://huggingface.co/openai/whisper-base"),
    SMALL("Whisper small（舊版）", OnDeviceBackend.WHISPER_CPP, ModelTier.LEGACY,
        "保留既有選擇與下載管理，約 488 MB；需要更高準確率時，先與上方候選用相同音訊比較。權重：MIT。",
        "https://huggingface.co/openai/whisper-small"),
    PARAFORMER("Paraformer（川渝方言專用）", OnDeviceBackend.SHERPA, ModelTier.LEGACY,
        "既有 2025-10-07 包為川渝方言微調版，非通用中英推薦；保留舊設定與已下載檔案，沒有標點。權重來源：WSChuan-ASR，Apache-2.0。",
        "https://huggingface.co/ASLP-lab/WSChuan-ASR"),

    ;
    val catalogLabel: String get() = "${tier.label} · $label"
}

/**
 * Cloud engine config for OpenRouter's `/audio/transcriptions` endpoint (JSON + base64 audio).
 * Only OpenRouter is supported — other OpenAI-compatible providers use multipart, which this engine
 * no longer sends. The [apiKey] is entered by the user and only ever stored on-device — never bundled
 * in source or the APK. Empty [baseUrl]/[apiKey] means the cloud engine isn't usable yet.
 */
data class CloudTranscribeConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    // false ⇒ upload 16 kHz mono WAV (best quality, big payload ⇒ short windows). true ⇒ encode each
    // window to m4a/AAC (~1/5 the bytes, faster/steadier upload on long audio, slight accuracy cost).
    // Cost is unaffected either way — OpenRouter bills by audio duration, not bytes.
    val compressAudio: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

data class AppSettings(
    val shareMode: ShareMode = ShareMode.ASK,
    val defaultMediaKind: MediaKind = MediaKind.VIDEO,
    val defaultVideoQuality: VideoQuality = VideoQuality.BEST,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val storageMode: StorageMode = StorageMode.DOWNLOADS,
    val safTreeUri: String? = null,
    val downloadAllWhenMultiple: Boolean = true,
    /** Null is unlimited. Read when a media download starts, in bytes per second. */
    val downloadRateLimitBytesPerSecond: Long? = null,
    val adRemovalEnabled: Boolean = true,
    val weightedAdAnalysisEnabled: Boolean = false,
    val autoUpdateYtDlpOnStartup: Boolean = false,
    val transcribeLanguage: TranscribeLanguage = TranscribeLanguage.AUTO,
    val transcribeEngine: TranscribeEngine = TranscribeEngine.ON_DEVICE,
    val transcribeModel: TranscribeModel = TranscribeModel.SENSE_VOICE,
    val cloud: CloudTranscribeConfig = CloudTranscribeConfig(),
) {
    val adRemovalPolicy: AdRemovalPolicy get() = when {
        !adRemovalEnabled -> AdRemovalPolicy.OFF
        weightedAdAnalysisEnabled -> AdRemovalPolicy.WEIGHTED
        else -> AdRemovalPolicy.CONFIRMED_ONLY
    }
}
