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

/** On-device whisper.cpp model. Larger = more accurate (esp. Chinese) but bigger download + slower. */
enum class TranscribeModel(val label: String) { BASE("base（快）"), SMALL("small（較準）") }

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
    val autoUpdateYtDlpOnStartup: Boolean = false,
    val transcribeLanguage: TranscribeLanguage = TranscribeLanguage.AUTO,
    val transcribeEngine: TranscribeEngine = TranscribeEngine.ON_DEVICE,
    val transcribeModel: TranscribeModel = TranscribeModel.BASE,
    val cloud: CloudTranscribeConfig = CloudTranscribeConfig(),
)
