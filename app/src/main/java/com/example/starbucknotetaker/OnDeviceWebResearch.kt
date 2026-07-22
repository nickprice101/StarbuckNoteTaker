package com.example.starbucknotetaker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class RenderedWebPage(
    val title: String,
    val url: String,
    val text: String,
)

internal fun interface RenderedPageLoader {
    suspend fun load(url: String): RenderedWebPage?
}

/**
 * Renders JavaScript-heavy HTTPS pages without exposing a JavaScript/native bridge, local files,
 * content providers, mixed content, popups, downloads, geolocation, or cross-scheme navigation.
 */
internal class AndroidRenderedPageLoader(context: Context) : RenderedPageLoader {
    private val appContext = context.applicationContext
    private val renderMutex = Mutex()

    override suspend fun load(url: String): RenderedWebPage? {
        if (!isPublicWebUrl(url)) return null
        return renderMutex.withLock {
            withTimeoutOrNull(RENDER_TIMEOUT_MS + RENDER_TIMEOUT_GRACE_MS) {
                renderOnMainThread(url)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderOnMainThread(url: String): RenderedWebPage? =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            val completed = AtomicBoolean(false)
            lateinit var timeout: Runnable

            fun destroyWebView() {
                handler.post {
                    webView?.apply {
                        stopLoading()
                        loadUrl("about:blank")
                        clearHistory()
                        removeAllViews()
                        destroy()
                    }
                    webView = null
                }
            }

            fun complete(result: RenderedWebPage?) {
                if (!completed.compareAndSet(false, true)) return
                handler.removeCallbacks(timeout)
                if (continuation.isActive) continuation.resume(result)
                destroyWebView()
            }

            timeout = Runnable { complete(null) }
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout)
                    destroyWebView()
                }
            }

            handler.post {
                if (!continuation.isActive) return@post
                val view = runCatching { WebView(appContext) }.getOrElse {
                    complete(null)
                    return@post
                }
                webView = view
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
                view.removeJavascriptInterface("searchBoxJavaBridge_")
                view.removeJavascriptInterface("accessibility")
                view.removeJavascriptInterface("accessibilityTraversal")
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setGeolocationEnabled(false)
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mediaPlaybackRequiresUserGesture = true
                    userAgentString = "$userAgentString StarbuckNoteTakerResearch/1.0"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
                }
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !isPublicWebUrl(request.url.toString())

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = if (request.url.scheme.equals("https", true)) {
                        null
                    } else {
                        emptyWebResponse()
                    }

                    override fun onReceivedSslError(
                        view: WebView,
                        handler: SslErrorHandler,
                        error: SslError,
                    ) {
                        handler.cancel()
                        complete(null)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) complete(null)
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (!isPublicWebUrl(url)) complete(null)
                    }

                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        if (!isPublicWebUrl(loadedUrl) || completed.get()) return
                        handler.postDelayed(
                            {
                                if (completed.get()) return@postDelayed
                                view.evaluateJavascript(EXTRACT_READABLE_PAGE_SCRIPT) { encoded ->
                                    complete(parseRenderedPage(encoded, loadedUrl))
                                }
                            },
                            RENDER_SETTLE_MS,
                        )
                    }
                }
                handler.postDelayed(timeout, RENDER_TIMEOUT_MS)
                view.loadUrl(url, mapOf("Accept-Language" to "en-US,en;q=0.8"))
            }
        }

    private fun parseRenderedPage(encoded: String?, fallbackUrl: String): RenderedWebPage? {
        if (encoded.isNullOrBlank() || encoded == "null") return null
        val payload = runCatching { JSONArray("[$encoded]").optString(0) }.getOrNull().orEmpty()
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val text = root.optString("text").replace(Regex("\\s+"), " ").trim()
        if (text.length < MIN_RENDERED_TEXT) return null
        val finalUrl = root.optString("url").takeIf(::isPublicWebUrl) ?: fallbackUrl
        return RenderedWebPage(
            title = root.optString("title").trim().take(120),
            url = finalUrl,
            text = text.take(MAX_RENDERED_TEXT),
        )
    }

    private fun emptyWebResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        Charsets.UTF_8.name(),
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private const val RENDER_TIMEOUT_MS = 15_000L
        private const val RENDER_TIMEOUT_GRACE_MS = 1_000L
        private const val RENDER_SETTLE_MS = 1_200L
        private const val MIN_RENDERED_TEXT = 80
        private const val MAX_RENDERED_TEXT = 100_000
        private val EXTRACT_READABLE_PAGE_SCRIPT = """
            (() => {
              const selectors = 'script,style,noscript,template,svg,canvas,nav,footer,aside,form,dialog,button,input,select,textarea,iframe,[aria-hidden="true"],.advertisement,.ads,.cookie,.cookie-banner,.newsletter,.share,.social';
              document.querySelectorAll(selectors).forEach(node => node.remove());
              const roots = Array.from(document.querySelectorAll('article,main,[role="main"],.article,.article-body,.article-content,.entry-content,.post-content'));
              const root = roots.sort((a, b) => (b.innerText || '').length - (a.innerText || '').length)[0] || document.body;
              return JSON.stringify({
                title: document.querySelector('meta[property="og:title"]')?.content || document.title || '',
                url: location.href,
                text: (root?.innerText || '').slice(0, 100000)
              });
            })()
        """.trimIndent()
    }
}

internal data class CachedWebPage(
    val title: String,
    val url: String,
    val text: String,
    val savedAt: Long = System.currentTimeMillis(),
)

internal interface WebResearchCache {
    fun getDiscovery(query: String, maxAgeMillis: Long): List<WebLookupEntry>?
    fun putDiscovery(query: String, results: List<WebLookupEntry>)
    fun getPage(url: String, maxAgeMillis: Long): CachedWebPage?
    fun putPage(page: CachedWebPage)
}

internal class MemoryWebResearchCache : WebResearchCache {
    private data class TimedDiscovery(val savedAt: Long, val results: List<WebLookupEntry>)

    private val discoveries = mutableMapOf<String, TimedDiscovery>()
    private val pages = mutableMapOf<String, CachedWebPage>()

    @Synchronized
    override fun getDiscovery(query: String, maxAgeMillis: Long): List<WebLookupEntry>? =
        discoveries[query.cacheKey()]?.takeIf { it.savedAt.isFresh(maxAgeMillis) }?.results

    @Synchronized
    override fun putDiscovery(query: String, results: List<WebLookupEntry>) {
        discoveries[query.cacheKey()] = TimedDiscovery(System.currentTimeMillis(), results)
    }

    @Synchronized
    override fun getPage(url: String, maxAgeMillis: Long): CachedWebPage? =
        pages[url]?.takeIf { it.savedAt.isFresh(maxAgeMillis) }

    @Synchronized
    override fun putPage(page: CachedWebPage) {
        pages[page.url] = page
    }
}

/** Small bounded disk cache for public search results and extracted page text. */
internal class DiskWebResearchCache(context: Context) : WebResearchCache {
    private val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    private val lock = Any()

    override fun getDiscovery(query: String, maxAgeMillis: Long): List<WebLookupEntry>? = synchronized(lock) {
        val root = readJson(DISCOVERY_PREFIX + query.cacheKey()) ?: return@synchronized null
        if (!root.optLong("savedAt").isFresh(maxAgeMillis)) return@synchronized null
        val items = root.optJSONArray("results") ?: return@synchronized emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val url = item.optString("url")
                if (isPublicWebUrl(url)) add(WebLookupEntry(item.optString("title"), url, ""))
            }
        }
    }

    override fun putDiscovery(query: String, results: List<WebLookupEntry>) = synchronized(lock) {
        val root = JSONObject().apply {
            put("savedAt", System.currentTimeMillis())
            put(
                "results",
                JSONArray().apply {
                    results.forEach { result ->
                        put(JSONObject().put("title", result.title).put("url", result.url))
                    }
                },
            )
        }
        writeJson(DISCOVERY_PREFIX + query.cacheKey(), root)
    }

    override fun getPage(url: String, maxAgeMillis: Long): CachedWebPage? = synchronized(lock) {
        val root = readJson(PAGE_PREFIX + url) ?: return@synchronized null
        val savedAt = root.optLong("savedAt")
        if (!savedAt.isFresh(maxAgeMillis)) return@synchronized null
        val cachedUrl = root.optString("url")
        val text = root.optString("text")
        if (!isPublicWebUrl(cachedUrl) || text.isBlank()) return@synchronized null
        CachedWebPage(root.optString("title"), cachedUrl, text, savedAt)
    }

    override fun putPage(page: CachedWebPage) = synchronized(lock) {
        val root = JSONObject().apply {
            put("savedAt", page.savedAt)
            put("title", page.title)
            put("url", page.url)
            put("text", page.text)
        }
        writeJson(PAGE_PREFIX + page.url, root)
    }

    private fun readJson(key: String): JSONObject? = runCatching {
        cacheFile(key).takeIf(File::isFile)?.readText()?.let(::JSONObject)
    }.getOrNull()

    private fun writeJson(key: String, value: JSONObject) {
        runCatching {
            directory.mkdirs()
            val destination = cacheFile(key)
            val temporary = File(directory, "${destination.name}.tmp")
            temporary.writeText(value.toString())
            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                destination.writeText(value.toString())
                temporary.delete()
            }
            prune()
        }
    }

    private fun cacheFile(key: String): File = File(directory, key.sha256() + ".json")

    private fun prune() {
        val files = directory.listFiles { file -> file.extension == "json" }.orEmpty()
        if (files.size <= MAX_CACHE_FILES) return
        files.sortedBy(File::lastModified).take(files.size - MAX_CACHE_FILES).forEach(File::delete)
    }

    companion object {
        private const val CACHE_DIRECTORY = "assistant_web_research"
        private const val DISCOVERY_PREFIX = "discovery:"
        private const val PAGE_PREFIX = "page:"
        private const val MAX_CACHE_FILES = 64
    }
}

private fun Long.isFresh(maxAgeMillis: Long): Boolean {
    if (this <= 0L) return false
    val age = (System.currentTimeMillis() - this).coerceAtLeast(0L)
    return maxAgeMillis == Long.MAX_VALUE || age <= maxAgeMillis
}

private fun String.cacheKey(): String = trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
