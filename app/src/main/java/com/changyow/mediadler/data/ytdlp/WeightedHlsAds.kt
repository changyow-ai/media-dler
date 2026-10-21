package com.changyow.mediadler.data.ytdlp

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Every executable input is bound before choosing a download checkpoint directory. */
internal object WeightedHlsAds {
    const val POLICY = "weighted-hls-v3"
    const val OCR_ENGINE = "mlkit-chinese-16.0.1"
    val ASSETS = listOf("weighted_hls_ads.py", "probe_hls_ad_features.py", "score_hls_ad_features.py",
        "hls_ad_filter.py", "hls_resume.py", "hls_media_validation.py", "ad_analysis_checkpoint.py", EightMapleHlsAds.CATALOG_ASSET)

    fun policyBytes(assets: Map<String, ByteArray>, knownCatalogEnabled: Boolean): ByteArray {
        require(assets.keys == ASSETS.toSet())
        return buildJsonObject {
            put("schema", 1)
            put("policy", POLICY)
            put("ocr_engine", OCR_ENGINE)
            // Change when the native OCR observation format or processing changes.
            put("ocr_adapter", "lines-confidence-top-left-v1")
            put("known_catalog_enabled", knownCatalogEnabled)
            putJsonObject("assets") { ASSETS.forEach { name -> put(name, digest(assets.getValue(name))) } }
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun identity(policy: ByteArray): String = "$POLICY\n${digest(policy)}"
    fun files(workDir: File) = EightMapleHlsAds.files(workDir, POLICY)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
