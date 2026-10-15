package com.changyow.mediadler.core

import com.changyow.mediadler.core.extract.ShopeeUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopeeUrlTest {
    private val product = ShopeeUrl.Product("123", "456")

    @Test fun canonicalAndSlugDropTracking() {
        assertEquals(product, ShopeeUrl.productOrNull("https://shopee.tw/product/123/456/?track=1#gallery"))
        assertEquals(product, ShopeeUrl.productOrNull("https://WWW.SHOPEE.TW:443/商品-name-i.123.456/?track=1"))
        assertEquals("https://shopee.tw/product/123/456", product.canonicalUrl)
        assertTrue(ShopeeUrl.isSupported(product.canonicalUrl))
        assertFalse(ShopeeUrl.isShare(product.canonicalUrl))
    }

    @Test fun recognizesOnlyBoundedTaiwanShortShares() {
        val share = "https://tw.shp.ee/Abc123/?track=1"
        assertTrue(ShopeeUrl.isShare(share))
        assertTrue(ShopeeUrl.isSupported(share))
        assertNull(ShopeeUrl.productOrNull(share))
        assertFalse(ShopeeUrl.isSupported("https://tw.shp.ee/Abc123/extra"))
        assertFalse(ShopeeUrl.isShare("https://tw.shp.ee/token_with_symbols"))
    }

    @Test fun shareHtmlDecodesOnlyHttpUrlJsonString() {
        val html = """<script>var CONFIG={httpUrl:"https:\/\/shopee.tw\/product\/123\/456?d_id=example\u0026x=1",deepLinkUrl:"shopee://product/999/999"};</script>"""
        assertEquals(product, ShopeeUrl.productFromShareHtml(html))
        assertNull(ShopeeUrl.productFromShareHtml("""var CONFIG={httpUrl:"https://example.com/product/123/456",deepLinkUrl:"https://shopee.tw/product/123/456"};"""))
        assertNull(ShopeeUrl.productFromShareHtml("""var CONFIG={deepLinkUrl:"https://shopee.tw/product/123/456"};"""))
    }

    @Test fun rejectsForeignMalformedAndAmbiguousTargets() {
        listOf(
            "http://shopee.tw/product/123/456",
            "https://shopee.tw.evil.example/product/123/456",
            "https://shopee.tw@evil.example/product/123/456",
            "https://user@shopee.tw/product/123/456",
            "https://shopee.tw:8443/product/123/456",
            "https://shopee.tw/product/123/456/extra",
            "https://shopee.tw/category/name-i.123.456",
            "https://shopee.tw/name%2fextra-i.123.456",
            "https://shopee.tw/product/notnumeric/456",
            "https://example.com/?next=https://shopee.tw/product/123/456",
            "https://tw.shp.ee.evil.example/Abc123",
        ).forEach { assertFalse(ShopeeUrl.isSupported(it), it) }
    }
}
