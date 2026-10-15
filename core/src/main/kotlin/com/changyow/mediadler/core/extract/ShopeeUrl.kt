package com.changyow.mediadler.core.extract

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Taiwan product permalinks and the short shares that resolve to them. */
object ShopeeUrl {
    private val IDS = Regex("[0-9]+")
    private val PRODUCT = Regex("/product/([0-9]+)/([0-9]+)/?")
    private val SLUG = Regex("/[^/]+-i\\.([0-9]+)\\.([0-9]+)/?")
    private val SHARE = Regex("/[A-Za-z0-9]+/?")
    private val ENCODED_SEPARATOR = Regex("%2f|%5c", RegexOption.IGNORE_CASE)
    private val HTTP_URL = Regex("""[,{]\s*(?:httpUrl|"httpUrl")\s*:\s*("(?:[^"\\]|\\.)*")""")

    data class Product(val shopId: String, val itemId: String) {
        init {
            require(IDS.matches(shopId) && IDS.matches(itemId))
        }

        val canonicalUrl: String get() = "https://shopee.tw/product/$shopId/$itemId"
    }

    fun productOrNull(url: String): Product? {
        val uri = httpsUri(url) ?: return null
        if (!uri.host.equals("shopee.tw", ignoreCase = true) &&
            !uri.host.equals("www.shopee.tw", ignoreCase = true)
        ) return null
        val path = uri.rawPath ?: return null
        if (ENCODED_SEPARATOR.containsMatchIn(path)) return null
        val match = PRODUCT.matchEntire(path) ?: SLUG.matchEntire(path) ?: return null
        return Product(match.groupValues[1], match.groupValues[2])
    }

    fun isShare(url: String): Boolean {
        val uri = httpsUri(url) ?: return false
        return uri.host.equals("tw.shp.ee", ignoreCase = true) && SHARE.matches(uri.rawPath.orEmpty())
    }

    fun isSupported(url: String): Boolean = productOrNull(url) != null || isShare(url)

    /** Decode only the JSON string in the share page's httpUrl property; never execute CONFIG. */
    fun productFromShareHtml(html: String): Product? {
        val encoded = HTTP_URL.find(html)?.groupValues?.get(1) ?: return null
        val value = runCatching { Json.parseToJsonElement(encoded).jsonPrimitive }
            .getOrNull()?.takeIf { it.isString }?.content ?: return null
        return productOrNull(value)
    }

    private fun httpsUri(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && it.host != null &&
            it.rawUserInfo == null && (it.port == -1 || it.port == 443)
    }
}
