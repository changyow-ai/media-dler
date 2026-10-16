package com.changyow.mediadler.data.ytdlp

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Immutable media snapshot consumed by the per-process native HLS adapter. */
internal object HlsResumeFiles {
    fun writeSession(workDir: File, hls: PreparedHlsResume): File {
        val directory = File(workDir, ".resume").apply { mkdirs() }
        val destination = File(directory, "session.json")
        val temporary = File(directory, "session.json.tmp")
        val value = buildJsonObject {
            put("schema", 1)
            put("identity", hls.identity)
            put("url", hls.resource.url)
            put("manifest", hls.manifest)
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
