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
)
