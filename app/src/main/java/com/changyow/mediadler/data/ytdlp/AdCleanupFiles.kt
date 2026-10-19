package com.changyow.mediadler.data.ytdlp

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class AdCleanupResult(val file: File, val removedAds: Int, val removedSeconds: Double)

/** Each attempt keeps its verified output inside the task's publication/recovery directory. */
internal class AdCleanupFiles private constructor(val source: File, val directory: File) {
    private val sourceSize = source.length()
    val script = File(directory, "ad_cleanup.py")
    val signatures = File(directory, "known_ads.json")
    val output = File(directory, "cleaned.mp4")
    val result = File(directory, "result.json")

    fun readResult(): AdCleanupResult {
        check(source.isFile && source.length() == sourceSize && result.isFile && result.length() in 1L..16_384L) {
            "廣告處理結果不完整，原下載資料已保留"
        }
        val data = Json.parseToJsonElement(result.readText()).jsonObject
        check(data["schema"]?.jsonPrimitive?.intOrNull == 1) { "廣告處理結果版本無效" }
        val count = data["removed_ads"]?.jsonPrimitive?.intOrNull ?: error("缺少廣告處理結果")
        val seconds = data["removed_seconds"]?.jsonPrimitive?.doubleOrNull ?: error("缺少廣告處理時間")
        check(seconds.isFinite()) { "廣告處理時間無效" }
        return when (data["status"]?.jsonPrimitive?.content) {
            "unchanged" -> {
                check(count == 0 && seconds == 0.0 && !output.exists()) { "未命中廣告時產生了不明成品" }
                AdCleanupResult(source, 0, 0.0)
            }
            "cleaned" -> {
                val size = data["output_size"]?.jsonPrimitive?.longOrNull
                check(count in 1..16 && seconds > 0 && output.isFile &&
                    output.length() in 1L until sourceSize && output.length() == size &&
                    !Files.isSameFile(source.toPath(), output.toPath())) {
                    "廣告處理成品無效，原下載資料已保留"
                }
                AdCleanupResult(output, count, seconds)
            }
            else -> error("廣告處理未完成，原下載資料已保留")
        }
    }

    companion object {
        fun create(source: File): AdCleanupFiles {
            require(source.isFile && source.length() > 0) { "找不到待處理的下載檔案" }
            val directory = Files.createTempDirectory(source.parentFile.toPath(), ".ad-cleanup-").toFile()
            return AdCleanupFiles(source, directory)
        }
    }
}
