package com.changyow.mediadler.data.shopee

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.changyow.mediadler.core.extract.ShopeeMediaParser
import com.changyow.mediadler.core.extract.ShopeeUrl
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.core.repo.MediaExtractor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Reads public product data in an app-owned WebView; never opens a browser or login page. */
class ShopeeExtractor(context: Context) : MediaExtractor {
    private val application = context.applicationContext
    private val scriptTemplate by lazy {
        application.assets.open("shopee_snapshot.js").bufferedReader().use { it.readText() }
    }

    override suspend fun extract(url: String): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            val product = resolveProduct(url)
            val script = scriptTemplate
                .replace("__SHOP_ID_JSON__", Json.encodeToString(product.shopId))
                .replace("__ITEM_ID_JSON__", Json.encodeToString(product.itemId))
            Result.success(readProduct(product, script))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun resolveProduct(url: String): ShopeeUrl.Product {
        ShopeeUrl.productOrNull(url)?.let { return it }
        return coroutineScope {
            val active = AtomicReference<HttpURLConnection?>()
            val request = async(Dispatchers.IO) { resolveShare(url, active) { ensureActive() } }
            try {
                withTimeoutOrNull(15_000) { request.await() }
                    ?: error("蝦皮分享連結解析逾時")
            } finally {
                request.cancel()
                active.getAndSet(null)?.disconnect()
            }
        }
    }

    private fun resolveShare(
        url: String,
        active: AtomicReference<HttpURLConnection?>,
        checkActive: () -> Unit,
    ): ShopeeUrl.Product {
        var current = url
        repeat(3) {
            checkActive()
            require(ShopeeUrl.isShare(current)) { "不是支援的台灣蝦皮商品連結" }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "media-dler Android")
            }
            active.set(connection)
            try {
                checkActive()
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = checkNotNull(connection.getHeaderField("Location")) {
                        "蝦皮分享連結缺少目標網址"
                    }
                    val next = URI(current).resolve(location).toString()
                    ShopeeUrl.productOrNull(next)?.let { return it }
                    require(ShopeeUrl.isShare(next)) { "蝦皮分享連結未指向商品" }
                    current = next
                } else {
                    check(status in 200..299) { "蝦皮分享連結回應 HTTP $status" }
                    val body = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 1_000_000) { "蝦皮分享頁面過大" }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                    return checkNotNull(ShopeeUrl.productFromShareHtml(body)) {
                        "蝦皮分享連結找不到商品網址"
                    }
                }
            } finally {
                active.compareAndSet(connection, null)
                connection.disconnect()
            }
        }
        error("蝦皮分享連結轉址過多")
    }

    private suspend fun readProduct(product: ShopeeUrl.Product, script: String): List<MediaItem> =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                var web: WebView? = null
                var done = false
                var evaluating = false

                fun finish(result: Result<List<MediaItem>>?) {
                    if (done) return
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    web?.let { view ->
                        runCatching { view.stopLoading() }
                        runCatching { view.destroy() }
                    }
                    web = null
                    if (result != null && continuation.isActive) continuation.resumeWith(result)
                }

                fun fail(message: String) = finish(Result.failure(IllegalStateException(message)))
                fun expected(url: String) = ShopeeUrl.productOrNull(url) == product
                fun snapshot() {
                    val view = web ?: return
                    if (done || evaluating) return
                    evaluating = true
                    view.evaluateJavascript(script) { encoded ->
                        if (done) return@evaluateJavascript
                        evaluating = false
                        val value = runCatching { Json.parseToJsonElement(encoded) as? JsonPrimitive }
                            .getOrNull()?.takeIf { it.isString }?.content
                        if (value != null && value.length <= 512_000) {
                            val items = ShopeeMediaParser.parse(value, product)
                            if (items.isNotEmpty()) finish(Result.success(items))
                        }
                    }
                }

                continuation.invokeOnCancellation { handler.post { finish(null) } }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                try {
                    val view = WebView(application)
                    web = view
                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(true)
                    }
                    view.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(v: WebView, dialog: Boolean, gesture: Boolean, message: android.os.Message) = false
                        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                            if (request.isForMainFrame && !expected(request.url.toString())) {
                                snapshot()
                                return true
                            }
                            return !isHttps(request.url)
                        }

                        override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (!isHttps(request.url) || (request.isForMainFrame && !expected(request.url.toString()))) {
                                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                            } else null

                        override fun onPageStarted(v: WebView, url: String, icon: Bitmap?) {
                            if (!expected(url)) fail("蝦皮商品頁未提供公開媒體資料") else snapshot()
                        }
                        override fun onPageCommitVisible(v: WebView, url: String) = snapshot()
                        override fun onPageFinished(v: WebView, url: String) = snapshot()
                        override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) fail("蝦皮商品頁連線失敗（${error.errorCode}）")
                        }
                        override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) fail("蝦皮商品頁回應 HTTP ${response.statusCode}")
                        }
                        override fun onReceivedSslError(v: WebView, sslHandler: SslErrorHandler, error: SslError) {
                            sslHandler.cancel()
                            fail("蝦皮商品頁的安全連線驗證失敗")
                        }
                        override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                            fail("蝦皮商品解析程序已中止")
                            return true
                        }
                    }
                    // The resolver stays detached; only the item's public initial JSON is read.
                    view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 1080, 1920)
                    handler.post(object : Runnable {
                        override fun run() {
                            if (done) return
                            snapshot()
                            if (!done) handler.postDelayed(this, 200)
                        }
                    })
                    handler.postDelayed({ fail("目前無法取得這則蝦皮商品的完整公開媒體資料") }, 20_000)
                    view.loadUrl(product.canonicalUrl)
                } catch (error: Exception) {
                    finish(Result.failure(error))
                }
            }
        }

    private fun isHttps(uri: Uri): Boolean = "https".equals(uri.scheme, ignoreCase = true) &&
        uri.host != null && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
}
