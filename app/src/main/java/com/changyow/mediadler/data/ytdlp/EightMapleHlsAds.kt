package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.download.HlsResumeGuard
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Job-owned input and frozen result of native, complete-content HLS ad verification. */
internal object EightMapleHlsAds {
    const val POLICY = "eightmaple-content-v1"
    const val CATALOG_ASSET = "hls_ad_fingerprints.json"

    fun policyIdentity(catalog: ByteArray): String = "$POLICY\n${digest(catalog)}"

    fun files(workDir: File): Files = Files(File(workDir, ".resume").apply { mkdirs() })

    class Files(val directory: File) {
        val script = File(directory, "hls_ad_filter.py")
        val catalog = File(directory, "ad-catalog.json")
        val input = File(directory, "ad-input.json")
        val plan = File(directory, "ad-plan.json")

        fun writeInput(hls: PreparedHlsResume, catalogBytes: ByteArray, rateLimit: Long? = null) {
            require(rateLimit == null || rateLimit > 0)
            atomicWrite(catalog, catalogBytes)
            val value = buildJsonObject {
                put("schema", 1)
                put("url", hls.resource.url)
                put("manifest", hls.manifest)
                put("identity", hls.identity)
                rateLimit?.let { put("rate_limit_bytes_per_second", it) }
                putJsonObject("headers") {
                    hls.resource.httpHeaders.forEach { (name, value) -> put(name, value) }
                }
            }
            atomicWrite(input, value.toString().toByteArray(Charsets.UTF_8))
        }

        fun readResult(original: PreparedHlsResume): PreparedHlsResume {
            check(plan.isFile && plan.length() in 1..4L * 1024 * 1024) { "廣告辨識結果不完整" }
            val value = Json.parseToJsonElement(plan.readText()).jsonObject
            check(value["schema"]?.jsonPrimitive?.int == 1 &&
                value["policy"]?.jsonPrimitive?.content == POLICY &&
                value["catalog_sha256"]?.jsonPrimitive?.content == digest(catalog.readBytes()) &&
                value["snapshot_identity"]?.jsonPrimitive?.content == original.identity) { "廣告辨識結果與來源不符" }
            val manifest = requireNotNull(value["manifest"]).jsonPrimitive.content
            val identity = requireNotNull(value["identity"]).jsonPrimitive.content
            val ads = requireNotNull(value["skipped_ads"]).jsonPrimitive.int
            val segments = requireNotNull(value["skipped_ad_segments"]).jsonPrimitive.int
            val seconds = requireNotNull(value["skipped_ad_seconds"]).jsonPrimitive.double
            check(identity.matches(Regex("[0-9a-f]{64}")) && HlsResumeGuard.fingerprint(manifest) != null &&
                ads in 0..16 && segments >= 0 && seconds.isFinite() && seconds >= 0 &&
                (ads > 0 && segments > 0 && seconds > 0 ||
                    ads == 0 && segments == 0 && seconds == 0.0 && manifest == original.manifest)) {
                "廣告辨識結果無效"
            }
            return original.copy(manifest = manifest, identity = identity,
                skippedAds = ads, skippedAdSegments = segments, skippedAdSeconds = seconds, adPlan = plan)
        }
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
