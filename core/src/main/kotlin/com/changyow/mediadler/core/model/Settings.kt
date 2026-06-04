package com.changyow.mediadler.core.model

enum class ShareMode { ONE_TAP, ASK }

enum class MediaKind { VIDEO, AUDIO }

enum class VideoQuality(val maxHeight: Int?) {
    BEST(null), P1080(1080), P720(720), P480(480), P360(360)
}

enum class AudioFormat(val ext: String) { MP3("mp3"), M4A("m4a") }

enum class StorageMode { DOWNLOADS, SAF }

data class AppSettings(
    val shareMode: ShareMode = ShareMode.ASK,
    val defaultMediaKind: MediaKind = MediaKind.VIDEO,
    val defaultVideoQuality: VideoQuality = VideoQuality.BEST,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val storageMode: StorageMode = StorageMode.DOWNLOADS,
    val safTreeUri: String? = null,
    val downloadAllWhenMultiple: Boolean = true,
    val autoUpdateYtDlpOnStartup: Boolean = false,
)
