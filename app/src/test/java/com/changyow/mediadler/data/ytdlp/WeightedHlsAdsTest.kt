package com.changyow.mediadler.data.ytdlp

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class WeightedHlsAdsTest {
    private val assets = WeightedHlsAds.ASSETS.associateWith { it.toByteArray() }

    @Test fun `every runtime change isolates frozen decisions and partial downloads`() {
        val policy = WeightedHlsAds.policyBytes(assets, false)
        assertContentEquals(policy, WeightedHlsAds.policyBytes(assets.toMap(), false))
        WeightedHlsAds.ASSETS.forEach { name ->
            val changed = assets + (name to (assets.getValue(name) + 1))
            assertNotEquals(WeightedHlsAds.identity(policy),
                WeightedHlsAds.identity(WeightedHlsAds.policyBytes(changed, false)), name)
        }
        assertNotEquals(WeightedHlsAds.identity(policy),
            WeightedHlsAds.identity(WeightedHlsAds.policyBytes(assets, true)))
        assertFailsWith<IllegalArgumentException> {
            WeightedHlsAds.policyBytes(assets - "score_hls_ad_features.py", false)
        }
    }

    @Test fun `policy binds bundled OCR and source-specific catalog permission`() {
        val policy = Json.parseToJsonElement(WeightedHlsAds.policyBytes(assets, false).decodeToString()).jsonObject
        assertEquals(WeightedHlsAds.POLICY, policy.getValue("policy").jsonPrimitive.content)
        assertEquals(WeightedHlsAds.OCR_ENGINE, policy.getValue("ocr_engine").jsonPrimitive.content)
        assertEquals(false, policy.getValue("known_catalog_enabled").jsonPrimitive.boolean)
        assertEquals(WeightedHlsAds.ASSETS.toSet(), policy.getValue("assets").jsonObject.keys)
    }
}
