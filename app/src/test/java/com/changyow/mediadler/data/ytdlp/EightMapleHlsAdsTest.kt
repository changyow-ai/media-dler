package com.changyow.mediadler.data.ytdlp

import com.changyow.mediadler.core.model.MediaResource
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test

class EightMapleHlsAdsTest {
    private val root = Files.createTempDirectory("hls-ad-plan-").toFile()
    private val files = EightMapleHlsAds.files(root)
    private val catalog = "verified-catalog".toByteArray()
    private val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"
    private val original = PreparedHlsResume(MediaResource("https://media.example/index.m3u8",
        mapOf("Referer" to "https://page.example/")), manifest, "1".repeat(64))

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun `catalog changes isolate work directory identity even with no matches`() {
        val policy = EightMapleHlsAds.policyIdentity(catalog)
        assertEquals(policy, EightMapleHlsAds.policyIdentity(catalog.copyOf()))
        assertNotEquals(policy, EightMapleHlsAds.policyIdentity(catalog + 1))
        assertTrue(policy.startsWith("eightmaple-content-v1\n"))
    }

    @Test fun `input retains original snapshot and headers without overwriting frozen decisions`() {
        files.plan.writeText("existing frozen decision")
        files.writeInput(original, catalog, 32768)
        val input = Json.parseToJsonElement(files.input.readText()).jsonObject
        assertEquals(manifest, input.getValue("manifest").jsonPrimitive.content)
        assertEquals(original.identity, input.getValue("identity").jsonPrimitive.content)
        assertEquals("32768", input.getValue("rate_limit_bytes_per_second").jsonPrimitive.content)
        assertEquals("https://page.example/", input.getValue("headers").jsonObject.getValue("Referer").jsonPrimitive.content)
        assertEquals("existing frozen decision", files.plan.readText())
        assertContentEquals(catalog, files.catalog.readBytes())
    }

    @Test fun `validated no match still uses policy specific final identity and cache plan`() {
        files.writeInput(original, catalog)
        writePlan()
        val prepared = files.readResult(original)
        assertEquals(manifest, prepared.manifest)
        assertEquals(0, prepared.skippedAds)
        assertEquals(files.plan, prepared.adPlan)
        assertNotEquals(original.identity, prepared.identity)
        val session = HlsResumeFiles.writeSession(root, prepared)
        val value = Json.parseToJsonElement(session.readText()).jsonObject
        assertEquals(prepared.identity, value.getValue("identity").jsonPrimitive.content)
        assertEquals(files.plan.absolutePath, value.getValue("ad_plan").jsonPrimitive.content)
    }

    @Test fun `invalid plan metadata cannot select a filtered manifest or checkpoint`() {
        files.writeInput(original, catalog)
        val changes = listOf(
            "schema" to "2", "snapshot_identity" to "bad", "catalog_sha256" to "bad",
            "policy" to "marker-only", "identity" to "invalid", "manifest" to manifest.replace("#EXT-X-ENDLIST\n", ""),
        )
        changes.forEach { (field, value) ->
            writePlan(field, value)
            assertFails("Invalid $field must reject plan") { files.readResult(original) }
        }
        writePlan("manifest", manifest.replace("a.ts", "different.ts"))
        assertFails("No-match result cannot change retained media") { files.readResult(original) }
    }

    @Test fun `ordinary HLS session has no ad plan and never rewrites checkpoint`() {
        val checkpoint = java.io.File(files.directory, "checkpoint.json").apply { writeText("committed") }
        val session = HlsResumeFiles.writeSession(root, original)
        assertFalse(Json.parseToJsonElement(session.readText()).jsonObject.containsKey("ad_plan"))
        assertFalse(Json.parseToJsonElement(session.readText()).jsonObject.containsKey("media_validation"))
        assertEquals("committed", checkpoint.readText())
    }

    @Test fun `filtered video has a fresh validation token while audio and full content do not`() {
        val filtered = original.copy(adPlan = files.plan, skippedAds = 1, skippedAdSegments = 1,
            skippedAdSeconds = 4.0)
        fun session(audioOnly: Boolean = false) = Json.parseToJsonElement(
            HlsResumeFiles.writeSession(root, filtered, audioOnly = audioOnly).readText()).jsonObject
        val first = session().getValue("media_validation").jsonObject
        assertEquals("video", first.getValue("media_mode").jsonPrimitive.content)
        assertEquals("1", first.getValue("schema").jsonPrimitive.content)
        assertNotEquals(first.getValue("token"), session().getValue("media_validation").jsonObject.getValue("token"))
        assertFalse(session(audioOnly = true).containsKey("media_validation"))
        assertFalse(Json.parseToJsonElement(HlsResumeFiles.writeSession(root, original,
            skipAdAnalysis = true).readText()).jsonObject.containsKey("media_validation"))
    }

    @Test fun `skip analysis session preserves every original segment and rejects a filtered plan`() {
        val completeManifest = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:4,\nprogramme.ts\n" +
            "#EXT-X-DISCONTINUITY\n#EXTINF:4,\nad.ts\n#EXT-X-ENDLIST\n"
        val complete = original.copy(manifest = completeManifest)
        val full = AdWorkPolicy.fullContentDirectory(root).apply { mkdirs() }
        files.plan.writeText("old filtered plan retained")
        val session = HlsResumeFiles.writeSession(full, complete, skipAdAnalysis = true)
        val value = Json.parseToJsonElement(session.readText()).jsonObject
        assertEquals(completeManifest, value.getValue("manifest").jsonPrimitive.content)
        assertEquals(original.identity, value.getValue("identity").jsonPrimitive.content)
        assertFalse(value.containsKey("ad_plan"))
        assertEquals("old filtered plan retained", files.plan.readText())
        assertFailsWith<IllegalStateException> {
            HlsResumeFiles.writeSession(full, complete.copy(adPlan = files.plan), skipAdAnalysis = true)
        }
        assertFailsWith<IllegalStateException> {
            HlsResumeFiles.writeSession(full, complete.copy(skippedAds = 1, skippedAdSegments = 1), skipAdAnalysis = true)
        }
        assertEquals(completeManifest, Json.parseToJsonElement(session.readText()).jsonObject
            .getValue("manifest").jsonPrimitive.content)
    }

    private fun writePlan(field: String? = null, replacement: String = "") {
        val value = buildJsonObject {
            put("schema", if (field == "schema") replacement.toInt() else 1)
            put("policy", if (field == "policy") replacement else EightMapleHlsAds.POLICY)
            put("catalog_sha256", if (field == "catalog_sha256") replacement else
                EightMapleHlsAds.policyIdentity(catalog).substringAfter('\n'))
            put("snapshot_identity", if (field == "snapshot_identity") replacement else original.identity)
            put("identity", if (field == "identity") replacement else "2".repeat(64))
            put("manifest", if (field == "manifest") replacement else manifest)
            put("skipped_ads", 0)
            put("skipped_ad_segments", 0)
            put("skipped_ad_seconds", 0.0)
        }
        files.plan.writeText(value.toString())
    }
}
