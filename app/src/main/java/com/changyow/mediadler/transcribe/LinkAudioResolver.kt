package com.changyow.mediadler.transcribe

import android.content.Context
import com.changyow.mediadler.core.extract.ThreadsUrl
import com.changyow.mediadler.core.repo.MediaExtractor
import com.changyow.mediadler.core.transcribe.SubtitleVtt
import com.changyow.mediadler.data.ytdlp.EngineInitializer
import com.changyow.mediadler.data.ytdlp.EngineState
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a shared URL into something the transcribe pipeline can consume: either ready-made captions
 * (YouTube CC shortcut — skips the engine entirely) or a downloaded best-audio file. Caption probing
 * is best-effort (YouTube often needs a PO token / gets blocked); anything that fails there simply
 * falls through to the audio download, which is the reliable path.
 */
class LinkAudioResolver(
    private val context: Context,
    private val engine: EngineInitializer,
    private val mediaExtractor: MediaExtractor,
) {
    sealed interface Resolved {
        /** Captions found — already plain text, plus the subtitle's language code (for opencc). */
        data class Captions(val text: String, val language: String?) : Resolved
        /** No captions; this best-audio file was downloaded for the engine. Caller deletes it. */
        data class Audio(val file: File) : Resolved
    }

    /**
     * Resolves [url]. First tries captions (instant, no engine); if none, downloads best audio,
     * reporting download fraction via [onDownloadProgress]. Throws on a hard failure.
     */
    suspend fun resolve(url: String, onDownloadProgress: (Float) -> Unit): Resolved =
        withContext(Dispatchers.IO) {
            (engine.ensureInit() as? EngineState.Failed)?.let { error(it.message) }
            // Threads has no yt-dlp extractor, so feeding the post URL straight to yt-dlp fails.
            // Resolve it through the media extractor first (which scrapes the shared post's direct
            // CDN video), then transcribe that concrete media URL. Other links keep the captions
            // shortcut + best-audio path, which works directly off the original URL.
            if (ThreadsUrl.embedUrlOrNull(url) != null) {
                Resolved.Audio(downloadAudio(resolveThreadsVideoUrl(url), onDownloadProgress))
            } else {
                tryCaptions(url) ?: Resolved.Audio(downloadAudio(url, onDownloadProgress))
            }
        }

    /**
     * Resolves a Threads post link to a direct video URL via the media extractor (the same path the
     * downloader uses, scoped to the shared post). Picks the first video; images/text can't be
     * transcribed. Throws a user-facing message when the post has no video.
     */
    private suspend fun resolveThreadsVideoUrl(url: String): String {
        val items = mediaExtractor.extract(url).getOrElse { throw it }
        val video = items.firstOrNull { !it.isImage }
            ?: error("這則 Threads 貼文沒有可轉文字的影片（可能只有圖片或純文字）")
        return video.sourceUrl
    }

    /** Best-effort subtitle fetch without downloading the video. Returns null when there are none. */
    private suspend fun tryCaptions(url: String): Resolved.Captions? {
        val dir = File(context.cacheDir, "transcribe/subs/${System.nanoTime()}").apply { mkdirs() }
        return try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--skip-download")
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--sub-langs", SUB_LANGS)
                addOption("--sub-format", "vtt")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("-o", File(dir, "cc.%(ext)s").absolutePath)
            }
            try {
                runInterruptible { YoutubeDL.getInstance().execute(request) }
            } catch (c: CancellationException) {
                throw c // honour cancellation instead of treating it as "no captions"
            } catch (t: Throwable) {
                return null // caption fetch failed (blocked / none) → caller falls back to audio
            }
            val chosen = dir.listFiles().orEmpty()
                .filter { it.extension.equals("vtt", ignoreCase = true) }
                .minByOrNull { priorityOf(it) } ?: return null
            val text = SubtitleVtt.toPlainText(chosen.readText())
            if (text.isBlank()) null else Resolved.Captions(text, langOf(chosen))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Downloads best audio as a MediaCodec-friendly m4a. Caller owns (and deletes) the file. */
    private suspend fun downloadAudio(url: String, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "transcribe/audio/${System.nanoTime()}").apply { mkdirs() }
        val processId = "transcribe-${System.nanoTime()}"
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            addOption("-x")
            addOption("--audio-format", "m4a")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-mtime")
            addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
        }
        try {
            runInterruptible {
                YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
                    if (progress >= 0f) onProgress((progress / 100f).coerceIn(0f, 1f))
                }
            }
        } catch (t: Throwable) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            dir.deleteRecursively()
            throw t
        }
        return dir.listFiles().orEmpty().firstOrNull { it.isFile && !it.name.endsWith(".part") }
            ?: error("音訊下載失敗：找不到輸出檔")
    }

    /** Subtitle language code from a `cc.<lang>.vtt` filename. */
    private fun langOf(file: File): String? =
        file.name.removeSuffix(".vtt").substringAfterLast('.', "").ifBlank { null }

    /** Lower is better; ranks a produced vtt by [LANG_PRIORITY] (original/zh first, else last). */
    private fun priorityOf(file: File): Int {
        val lang = langOf(file)?.lowercase() ?: return Int.MAX_VALUE
        val idx = LANG_PRIORITY.indexOfFirst { lang == it || lang.startsWith("$it-") }
        return if (idx >= 0) idx else LANG_PRIORITY.size
    }

    private companion object {
        const val SUB_LANGS = "zh-Hant,zh-TW,zh-HK,zh,zh-Hans,zh-CN,en,en-US,ja,ko"
        val LANG_PRIORITY = listOf("zh-hant", "zh-tw", "zh-hk", "zh", "zh-hans", "zh-cn", "en", "ja", "ko")
    }
}
