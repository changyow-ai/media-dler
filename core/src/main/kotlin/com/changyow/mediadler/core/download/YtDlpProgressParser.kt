package com.changyow.mediadler.core.download

import com.changyow.mediadler.core.model.DownloadPhase
import com.changyow.mediadler.core.model.DownloadProgress
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Read explicit byte counts instead of the SDK's cached percentage or an estimated total. */
object YtDlpProgressParser {
    const val PREFIX = "media-dler-progress:"
    const val DOWNLOAD_TEMPLATE = "download:" + PREFIX +
        "{\"status\":%(progress.status)j,\"downloaded_bytes\":%(progress.downloaded_bytes|null)j," +
        "\"total_bytes\":%(progress.total_bytes|null)j,\"fragment_index\":%(progress.fragment_index|null)j," +
        "\"fragment_count\":%(progress.fragment_count|null)j}"
    const val POSTPROCESS_TEMPLATE = "postprocess:media-dler-phase:%(progress.status)s"
    private val postprocessor = Regex("""^\[(?:Merger|VideoRemuxer|ExtractAudio|Fixup\w*|VideoConvertor)]""")

    fun parse(line: String): DownloadProgress? {
        val value = line.trim()
        if (value.startsWith("media-dler-phase:") || postprocessor.containsMatchIn(value)) {
            return DownloadProgress(DownloadPhase.PROCESSING, detail = "正在封裝與處理媒體")
        }
        if (!value.startsWith(PREFIX)) return null
        val data = runCatching { Json.parseToJsonElement(value.removePrefix(PREFIX)) as? JsonObject }.getOrNull() ?: return null
        val status = (data["status"] as? JsonPrimitive)?.contentOrNull
        if (status == "finished") return DownloadProgress(DownloadPhase.PROCESSING, detail = "下載完成，正在處理媒體")
        if (status != "downloading") return null
        fun number(key: String): Long? = (data[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        val downloaded = number("downloaded_bytes")
        val total = number("total_bytes")?.takeIf { it > 0 }
        val fragment = number("fragment_index")
        val count = number("fragment_count")?.takeIf { it > 0 }
        val amount = when {
            downloaded != null && total != null -> "${bytes(downloaded)} / ${bytes(total)}"
            downloaded != null -> "已下載 ${bytes(downloaded)}"
            else -> "正在下載"
        }
        val detail = if (fragment != null && count != null) "$amount · 片段 $fragment/$count" else amount
        val fraction = if (downloaded != null && total != null) (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) else -1f
        return DownloadProgress(DownloadPhase.DOWNLOADING, fraction, detail, downloaded, total)
    }

    fun bytes(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1f GB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.ROOT, "%.1f KB", value / 1_000.0)
        else -> "$value B"
    }
}
