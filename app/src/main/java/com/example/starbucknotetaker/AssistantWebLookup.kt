package com.example.starbucknotetaker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject

/**
 * Internet research performed entirely by the Android app. Search providers only discover public
 * URLs; OkHttp, Jsoup, and a sandboxed WebView fallback extract and rank the page text on-device.
 */
internal class AssistantWebLookup(
    private val httpClient: HttpClient = OkHttpWebClient(),
    private val renderedPageLoader: RenderedPageLoader = RenderedPageLoader { null },
    private val researchCache: WebResearchCache = MemoryWebResearchCache(),
    private val searchProviders: List<WebSearchProvider> = defaultSearchProviders(httpClient),
    private val internetAvailable: () -> Boolean = { true },
) : WebResearcher {
    constructor(context: Context) : this(
        httpClient = OkHttpWebClient(),
        renderedPageLoader = AndroidRenderedPageLoader(context.applicationContext),
        researchCache = DiskWebResearchCache(context.applicationContext),
        internetAvailable = { context.applicationContext.hasValidatedInternetConnection() },
    )

    override suspend fun lookup(question: String): WebLookupResult = withContext(Dispatchers.IO) {
        val query = normalizeQuery(question)
        if (query.isBlank()) {
            return@withContext WebLookupResult(
                query = question,
                results = emptyList(),
                error = "Empty research query",
                errorKind = WebLookupErrorKind.NO_RESULTS,
            )
        }
        if (!internetAvailable()) return@withContext WebLookupResult.offline(query)

        val directUrls = extractUrls(question, treatUnterminatedAsComplete = true)
            .map { it.normalizedUrl }
            .filter(::isPublicWebUrl)
            .distinct()
            .take(MAX_RESULTS)
        val discovered = if (directUrls.isNotEmpty()) {
            directUrls.map { url -> WebLookupEntry(sourceLabel(url), url, "") }
        } else {
            discover(query)
        }
        if (discovered.isEmpty()) {
            return@withContext WebLookupResult(
                query = query,
                results = emptyList(),
                error = "No relevant public web pages were found.",
                errorKind = WebLookupErrorKind.NO_RESULTS,
            )
        }

        val results = supervisorScope {
            discovered.take(MAX_RESULTS).map { source ->
                async { extractSource(source, query) }
            }.awaitAll()
        }.filterNotNull().distinctBy { it.url }.take(MAX_RESULTS)

        if (results.isEmpty()) {
            WebLookupResult(
                query = query,
                results = emptyList(),
                error = "The phone could not extract readable content from the discovered pages.",
                errorKind = WebLookupErrorKind.NO_RESULTS,
            )
        } else {
            WebLookupResult(query = query, results = results)
        }
    }

    private suspend fun discover(query: String): List<WebLookupEntry> {
        researchCache.getDiscovery(query, DISCOVERY_CACHE_TTL_MS)?.let { cached ->
            if (cached.isNotEmpty()) return cached
        }
        val discovered = supervisorScope {
            searchProviders.map { provider ->
                async { runCatching { provider.search(query) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }.filter { isPublicWebUrl(it.url) }
            .distinctBy { it.url.trimEnd('/') }
            .take(MAX_RESULTS)
        if (discovered.isNotEmpty()) researchCache.putDiscovery(query, discovered)
        return discovered
    }

    private suspend fun extractSource(source: WebLookupEntry, query: String): WebLookupEntry? {
        val freshCached = researchCache.getPage(source.url, PAGE_CACHE_TTL_MS)
        if (freshCached != null) return freshCached.toLookupEntry(query, source.title)

        val staticPage = runCatching { fetchStaticPage(source.url) }.getOrNull()
        var extracted = staticPage?.let(::extractReadablePage)
        if (extracted == null || extracted.text.length < WEBVIEW_FALLBACK_THRESHOLD) {
            val rendered = runCatching { renderedPageLoader.load(staticPage?.url ?: source.url) }.getOrNull()
            val renderedExtract = rendered?.let {
                ExtractedWebPage(
                    title = shortSourceLabel(it.title.ifBlank { source.title }),
                    url = it.url.takeIf(::isPublicWebUrl) ?: source.url,
                    text = cleanReadableText(it.text),
                )
            }?.takeIf { it.text.length >= MIN_READABLE_TEXT }
            if (renderedExtract != null && renderedExtract.text.length > extracted?.text.orEmpty().length) {
                extracted = renderedExtract
            }
        }

        val usable = extracted?.takeIf { it.text.length >= MIN_READABLE_TEXT }
            ?: researchCache.getPage(source.url, Long.MAX_VALUE)?.toExtractedPage()
            ?: return null
        researchCache.putPage(CachedWebPage(usable.title, usable.url, usable.text))
        return usable.toLookupEntry(query, source.title)
    }

    private suspend fun fetchStaticPage(url: String): DownloadedWebPage? {
        val response = httpClient.get(url, PAGE_HEADERS, MAX_PAGE_RESPONSE_BYTES)
        if (response.statusCode !in 200..299 || response.body.isEmpty()) return null
        val finalUrl = response.finalUrl.takeIf(::isPublicWebUrl) ?: url
        val contentType = response.contentType.lowercase(Locale.US)
        if (contentType.isNotBlank() &&
            !contentType.contains("html") &&
            !contentType.contains("text/") &&
            !contentType.contains("json")
        ) return null
        return DownloadedWebPage(finalUrl, response.body, contentType)
    }

    private fun extractReadablePage(page: DownloadedWebPage): ExtractedWebPage? {
        if (page.contentType.contains("json")) {
            val text = cleanReadableText(page.body.toString(Charsets.UTF_8))
            return text.takeIf { it.length >= MIN_READABLE_TEXT }?.let {
                ExtractedWebPage(sourceLabel(page.url), page.url, it.take(MAX_EXTRACTED_TEXT))
            }
        }
        val document = runCatching {
            Jsoup.parse(ByteArrayInputStream(page.body), null, page.url)
        }.getOrNull() ?: return null
        document.select(REMOVED_CONTENT_SELECTORS).remove()
        val root = readableRoot(document) ?: return null
        val blocks = root.select(READABLE_BLOCK_SELECTORS)
            .map(Element::text)
            .map(::cleanReadableText)
            .filter { it.length >= MIN_BLOCK_TEXT }
            .distinct()
        val text = (if (blocks.isNotEmpty()) blocks.joinToString("\n") else root.text())
            .let(::cleanReadableTextPreservingLines)
            .take(MAX_EXTRACTED_TEXT)
        if (text.length < MIN_READABLE_TEXT) return null
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.title()
        return ExtractedWebPage(shortSourceLabel(title.ifBlank { sourceLabel(page.url) }), page.url, text)
    }

    private fun readableRoot(document: Document): Element? {
        val candidates = document.select(READABLE_ROOT_SELECTORS)
            .filter { it.text().length >= MIN_READABLE_TEXT }
        return candidates.maxByOrNull { it.text().length } ?: document.body()
    }

    internal interface HttpClient {
        suspend fun get(
            url: String,
            headers: Map<String, String> = emptyMap(),
            maxBytes: Int,
        ): WebLookupHttpResponse
    }

    private class OkHttpWebClient : HttpClient {
        private val client = OkHttpClient.Builder()
            .dns(PublicOnlyDns)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            maxBytes: Int,
        ): WebLookupHttpResponse = suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.get().build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                val bytes = it.body?.byteStream()?.use { stream ->
                                    stream.readBytesLimited(maxBytes)
                                } ?: ByteArray(0)
                                WebLookupHttpResponse(
                                    statusCode = it.code,
                                    body = bytes,
                                    finalUrl = it.request.url.toString(),
                                    contentType = it.header("Content-Type").orEmpty(),
                                )
                            }
                        }
                        if (!continuation.isActive) return
                        result.fold(continuation::resume) {
                            continuation.resumeWith(Result.failure(it))
                        }
                    }
                },
            )
        }
    }

    companion object {
        const val INTERNET_REQUIRED_MESSAGE =
            "This question requires internet research to answer properly. " +
                "Connect this phone to the internet, then try again."

        fun shouldLookup(question: String): Boolean {
            val trimmed = question.trim()
            if (trimmed.isBlank()) return false
            if (extractUrls(trimmed, treatUnterminatedAsComplete = true).isNotEmpty()) return true
            return requiresInternet(question) ||
                (FACTUAL_QUESTION_REGEX.containsMatchIn(trimmed) &&
                    !NON_LOOKUP_QUESTION_REGEX.containsMatchIn(trimmed))
        }

        /** These requests cannot be answered correctly from a potentially stale on-device model. */
        fun requiresInternet(question: String): Boolean {
            val trimmed = question.trim()
            if (trimmed.isBlank()) return false
            return extractUrls(trimmed, treatUnterminatedAsComplete = true).isNotEmpty() ||
                EXPLICIT_LOOKUP_REGEX.containsMatchIn(trimmed) ||
                CURRENT_INFO_REGEX.containsMatchIn(trimmed)
        }

        fun answerNeedsResearch(answer: String): Boolean = UNCERTAIN_ANSWER_REGEX.containsMatchIn(answer)

        fun mergeWithNoteContext(noteContext: String?, webLookup: WebLookupResult): String = buildString {
            val note = noteContext?.trim().orEmpty()
            if (note.isNotEmpty()) {
                appendLine("Note context:")
                appendLine(note)
                appendLine()
            }
            append(webLookup.toPromptContext())
        }.trim()

        fun quickAnswer(question: String, webLookup: WebLookupResult): String {
            if (webLookup.results.isEmpty()) return INTERNET_REQUIRED_MESSAGE
            val primary = webLookup.results.first()
            return buildString {
                appendLine(primary.snippet.ifBlank { "Research results for ${question.trim()}." })
                appendLine()
                appendLine("Sources:")
                webLookup.results.take(3).forEach { appendLine("- ${it.markdownLink()}") }
            }.trim()
        }

        fun appendMarkdownSources(answer: String, webLookup: WebLookupResult): String {
            val missing = webLookup.results.filterNot { answer.contains(it.url, ignoreCase = true) }.take(3)
            if (missing.isEmpty()) return answer.trim()
            return buildString {
                append(answer.trim())
                if (isNotEmpty()) appendLine().appendLine()
                appendLine("Sources:")
                missing.forEach { appendLine("- ${it.markdownLink()}") }
            }.trim()
        }

        private fun defaultSearchProviders(httpClient: HttpClient): List<WebSearchProvider> = listOf(
            DuckDuckGoSearchProvider(httpClient),
            WikipediaSearchProvider(httpClient),
        )
    }
}

internal fun interface WebResearcher {
    suspend fun lookup(question: String): WebLookupResult
}

internal fun interface WebSearchProvider {
    suspend fun search(query: String): List<WebLookupEntry>
}

private class DuckDuckGoSearchProvider(
    private val httpClient: AssistantWebLookup.HttpClient,
) : WebSearchProvider {
    override suspend fun search(query: String): List<WebLookupEntry> {
        val url = "https://html.duckduckgo.com/html/?q=${urlEncode(query)}"
        val response = httpClient.get(url, SEARCH_HEADERS, MAX_SEARCH_RESPONSE_BYTES)
        if (response.statusCode !in 200..299) return emptyList()
        val document = Jsoup.parse(ByteArrayInputStream(response.body), null, url)
        return document.select("a.result__a")
            .mapNotNull { link ->
                val href = resolveProtocolRelativeUrl(link.attr("href"))
                    .let(::resolveDuckDuckGoRedirect)
                    .takeIf(::isPublicWebUrl)
                    ?: return@mapNotNull null
                val title = shortSourceLabel(link.text())
                if (title.isBlank()) null else WebLookupEntry(title, href, "")
            }
            .distinctBy { it.url }
            .take(MAX_RESULTS)
    }
}

private class WikipediaSearchProvider(
    private val httpClient: AssistantWebLookup.HttpClient,
) : WebSearchProvider {
    override suspend fun search(query: String): List<WebLookupEntry> {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json" +
            "&srlimit=$MAX_RESULTS&srsearch=${urlEncode(query)}"
        val response = httpClient.get(url, SEARCH_HEADERS, MAX_SEARCH_RESPONSE_BYTES)
        if (response.statusCode !in 200..299) return emptyList()
        val search = JSONObject(response.body.toString(Charsets.UTF_8))
            .optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return buildList {
            for (index in 0 until search.length()) {
                val title = search.optJSONObject(index)?.optString("title")?.trim().orEmpty()
                if (title.isNotBlank()) {
                    add(
                        WebLookupEntry(
                            shortSourceLabel(title),
                            "https://en.wikipedia.org/wiki/${urlEncodePath(title.replace(' ', '_'))}",
                            "",
                        ),
                    )
                }
            }
        }
    }
}

internal data class WebLookupHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val finalUrl: String = "",
    val contentType: String = "text/html; charset=utf-8",
)

internal data class WebLookupEntry(val title: String, val url: String, val snippet: String) {
    fun markdownLink(): String = "[${title.take(48).ifBlank { "Source" }}]($url)"
}

internal enum class WebLookupErrorKind { OFFLINE, NO_RESULTS }

internal data class WebLookupResult(
    val query: String,
    val results: List<WebLookupEntry>,
    val error: String? = null,
    val errorKind: WebLookupErrorKind? = null,
) {
    fun toPromptContext(): String = buildString {
        appendLine("On-device web research for: $query")
        if (results.isEmpty()) {
            appendLine("No research results were available.")
            error?.let { appendLine("Research error: $it") }
            return@buildString
        }
        appendLine("Use only relevant facts below. Cite sources as abbreviated Markdown links.")
        results.forEachIndexed { index, result ->
            appendLine()
            appendLine("Source ${index + 1}: ${result.title}")
            appendLine("URL: ${result.url}")
            appendLine("Extracted text: ${result.snippet}")
        }
    }.trim()

    fun progressPreview(): String = results.take(2).joinToString("\n") { result ->
        "${result.title}: ${result.snippet}"
    }.take(500)

    companion object {
        fun offline(query: String): WebLookupResult = WebLookupResult(
            query = query,
            results = emptyList(),
            error = "No validated internet connection is available.",
            errorKind = WebLookupErrorKind.OFFLINE,
        )
    }
}

private data class DownloadedWebPage(
    val url: String,
    val body: ByteArray,
    val contentType: String,
)

private data class ExtractedWebPage(
    val title: String,
    val url: String,
    val text: String,
) {
    fun toLookupEntry(query: String, fallbackTitle: String): WebLookupEntry = WebLookupEntry(
        title = shortSourceLabel(title.ifBlank { fallbackTitle }),
        url = url,
        snippet = relevantExcerpt(text, query),
    )
}

private object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(InetAddress::isPrivateOrLocal)) {
            throw UnknownHostException("Private and local network targets are not allowed")
        }
        return addresses
    }
}

internal fun isPublicWebUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host?.lowercase(Locale.US).orEmpty()
    uri.scheme.equals("https", ignoreCase = true) && host.isNotBlank() &&
        host != "localhost" && host != "0.0.0.0" &&
        !host.matches(Regex("^127(?:\\.\\d{1,3}){3}$")) &&
        !host.startsWith("10.") && !host.startsWith("192.168.") &&
        !Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) &&
        !host.equals("::1") && !host.startsWith("[")
}.getOrDefault(false)

private fun InetAddress.isPrivateOrLocal(): Boolean =
    isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress ||
        hostAddress.orEmpty().lowercase(Locale.US).let { it.startsWith("fc") || it.startsWith("fd") }

private fun CachedWebPage.toExtractedPage(): ExtractedWebPage = ExtractedWebPage(title, url, text)

private fun CachedWebPage.toLookupEntry(query: String, fallbackTitle: String): WebLookupEntry =
    toExtractedPage().toLookupEntry(query, fallbackTitle)

private fun normalizeQuery(question: String): String = question
    .replace(EXPLICIT_LOOKUP_REGEX, " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { question.trim() }

private fun relevantExcerpt(text: String, query: String): String {
    val terms = query.lowercase(Locale.US)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in QUERY_STOP_WORDS }
        .toSet()
    val paragraphs = text.lines()
        .map(::cleanReadableText)
        .filter { it.length >= MIN_BLOCK_TEXT }
    return paragraphs
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<String>> { paragraph ->
                val lower = paragraph.value.lowercase(Locale.US)
                terms.sumOf { term -> if (lower.contains(term)) term.length else 0 }
            }.thenBy { it.index },
        )
        .take(MAX_EXCERPT_PARAGRAPHS)
        .sortedBy { it.index }
        .joinToString(" ") { it.value }
        .let(::cleanReadableText)
        .take(MAX_EXCERPT_TEXT)
        .trim()
}

private fun cleanReadableText(value: String): String = value.replace(Regex("\\s+"), " ").trim()

private fun cleanReadableTextPreservingLines(value: String): String = value.lines()
    .map(::cleanReadableText)
    .filter(String::isNotBlank)
    .joinToString("\n")

private fun resolveProtocolRelativeUrl(value: String): String = when {
    value.startsWith("//") -> "https:$value"
    else -> value
}

private fun resolveDuckDuckGoRedirect(rawUrl: String): String {
    if (!rawUrl.contains("uddg=")) return rawUrl
    val encoded = rawUrl.substringAfter("uddg=").substringBefore('&')
    return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrElse { rawUrl }
}

private fun sourceLabel(url: String): String = runCatching {
    URI(url).host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
}.getOrDefault("Source")

private fun shortSourceLabel(value: String): String = value
    .replace(Regex("[\\[\\]()]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(48)
    .ifBlank { "Source" }

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun urlEncodePath(value: String): String =
    value.split('/').joinToString("/") { urlEncode(it).replace("+", "%20") }

private fun Context.hasValidatedInternetConnection(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(chunk)
        if (read <= 0) break
        val remaining = maxBytes - total
        if (remaining <= 0) break
        val toWrite = minOf(read, remaining)
        buffer.write(chunk, 0, toWrite)
        total += toWrite
        if (toWrite < read || total >= maxBytes) break
    }
    return buffer.toByteArray()
}

private const val MAX_RESULTS = 4
private const val MAX_SEARCH_RESPONSE_BYTES = 512 * 1024
private const val MAX_PAGE_RESPONSE_BYTES = 2 * 1024 * 1024
private const val MAX_EXTRACTED_TEXT = 100_000
private const val MAX_EXCERPT_TEXT = 1_200
private const val MAX_EXCERPT_PARAGRAPHS = 4
private const val MIN_BLOCK_TEXT = 35
private const val MIN_READABLE_TEXT = 80
private const val WEBVIEW_FALLBACK_THRESHOLD = 500
private const val PAGE_CACHE_TTL_MS = 30 * 60 * 1000L
private const val DISCOVERY_CACHE_TTL_MS = 30 * 60 * 1000L
private const val READABLE_ROOT_SELECTORS =
    "article, main, [role=main], .article, .article-body, .article-content, .entry-content, .post-content"
private const val READABLE_BLOCK_SELECTORS = "h1, h2, h3, h4, p, li, blockquote, pre, td"
private const val REMOVED_CONTENT_SELECTORS =
    "script, style, noscript, template, svg, canvas, nav, footer, aside, form, dialog, " +
        "button, input, select, textarea, iframe, [aria-hidden=true], .advertisement, .ads, " +
        ".cookie, .cookie-banner, .newsletter, .share, .social"
private val SEARCH_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile Safari/537.36 StarbuckNoteTaker/1.0",
    "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5",
    "Accept-Language" to "en-US,en;q=0.8",
)
private val PAGE_HEADERS = SEARCH_HEADERS + mapOf("Cache-Control" to "max-age=0")
private val QUERY_STOP_WORDS = setOf(
    "the", "and", "for", "that", "this", "with", "what", "when", "where", "which", "who", "how",
)
private val CURRENT_INFO_REGEX = Regex(
    "\\b(latest|current|currently|today|tonight|yesterday|tomorrow|news|weather|forecast|" +
        "score|scores|schedule|price|stock|version|release|released|update|updates|recent|newest|now)\\b",
    RegexOption.IGNORE_CASE,
)
private val EXPLICIT_LOOKUP_REGEX = Regex(
    "\\b(search|web|internet|online|look\\s*up|lookup|google|source|sources|cite|citation)\\b",
    RegexOption.IGNORE_CASE,
)
private val FACTUAL_QUESTION_REGEX = Regex(
    "^\\s*(?:who|what|when|where|which|how\\s+many|how\\s+much|name|list)\\b",
    RegexOption.IGNORE_CASE,
)
private val NON_LOOKUP_QUESTION_REGEX = Regex(
    "\\b(?:should|could|would|this\\s+note|current\\s+note|rewrite|reformat|format|" +
        "summari[sz]e|brainstorm|draft|create)\\b",
    RegexOption.IGNORE_CASE,
)
private val UNCERTAIN_ANSWER_REGEX = Regex(
    "\\b(?:i (?:do not|don't) know|i(?:'m| am) not sure|cannot determine|can't determine|" +
        "cannot answer|can't answer|cannot verify|can't verify|unable to answer|" +
        "(?:do not|don't) have enough information|insufficient information|not enough information|" +
        "not (?:available|provided) in (?:the )?(?:note|context)|knowledge cutoff|" +
        "outside my knowledge|no reliable information)\\b",
    RegexOption.IGNORE_CASE,
)
