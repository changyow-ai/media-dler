package com.changyow.mediadler.data.ytdlp

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Immutable media snapshot consumed by the per-process native HLS adapter. */
internal object HlsResumeFiles {
    fun writeSession(workDir: File, hls: PreparedHlsResume, skipAdAnalysis: Boolean = false,
        audioOnly: Boolean = false): File {
        check(!skipAdAnalysis || hls.adPlan == null && hls.skippedAds == 0 &&
            hls.skippedAdSegments == 0 && hls.skippedAdSeconds == 0.0) {
            "略過分析必須使用完整原始片段，不能沿用去廣告的下載計畫"
        }
        val directory = File(workDir, ".resume").apply { mkdirs() }
        val destination = File(directory, "session.json")
        val temporary = File(directory, "session.json.tmp")
        val value = buildJsonObject {
            put("schema", 1)
            put("identity", hls.identity)
            put("url", hls.resource.url)
            put("manifest", hls.manifest)
            hls.adPlan?.let { put("ad_plan", it.absolutePath) }
            if (hls.skippedAds > 0 && !audioOnly) putJsonObject("media_validation") {
                put("schema", 1)
                put("token", UUID.randomUUID().toString())
                put("media_mode", "video")
            }
        }.toString()
        FileOutputStream(temporary).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return destination
    }
}
