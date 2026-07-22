package com.example.starbucknotetaker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** Internet research used by the assistant. Search discovers pages; Crawl4AI extracts their text. */
internal class AssistantWebLookup(
    private val httpClient: HttpClient = OkHttpWebClient(),
    private val crawl4AiConfig: Crawl4AiConfig = Crawl4AiConfig.fromBuildConfig(),
    private val internetAvailable: () -> Boolean = { true },
) : WebResearcher {
    constructor(context: Context) : this(
        crawl4AiConfig = Crawl4AiConfig.fromBuildConfig(),
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
        if (!internetAvailable()) {
            return@withContext WebLookupResult.offline(query)
        }
        if (!crawl4AiConfig.isConfigured) {
            return@withContext WebLookupResult(
                query = query,
                results = emptyList(),
                error = "The Crawl4AI research service is not configured.",
                errorKind = WebLookupErrorKind.RESEARCH_UNAVAILABLE,
            )
        }

        val directUrls = extractUrls(question, treatUnterminatedAsComplete = true)
            .map { it.normalizedUrl }
            .filter(::isPublicWebUrl)
            .distinct()
            .take(MAX_RESULTS)
        val discovered = if (directUrls.isNotEmpty()) {
            directUrls.map { url ->
                WebLookupEntry(title = sourceLabel(url), url = url, snippet = "")
            }
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

        crawlWithCrawl4Ai(query, discovered)
    }

    private suspend fun discover(query: String): List<WebLookupEntry> = supervisorScope {
        val attempts = listOf(
            async { discoverSafely(query, "DuckDuckGo", ::searchDuckDuckGoHtml) },
            async { discoverSafely(query, "Wikipedia", ::searchWikipedia) },
        ).toMutableList()
        while (attempts.isNotEmpty()) {
            val (completed, result) = select<Pair<Deferred<List<WebLookupEntry>>, List<WebLookupEntry>>> {
                attempts.forEach { attempt -> attempt.onAwait { attempt to it } }
            }
            attempts.remove(completed)
            if (result.isNotEmpty()) {
                attempts.forEach { it.cancel() }
                return@supervisorScope result
            }
        }
        emptyList()
    }

    private suspend fun discoverSafely(
        query: String,
        provider: String,
        search: suspend (String) -> List<WebLookupEntry>,
    ): List<WebLookupEntry> = runCatching { search(query) }
        .getOrElse { failure ->
            @Suppress("UNUSED_VARIABLE")
            val ignored = "$provider unavailable: ${failure.message.orEmpty()}"
            emptyList()
        }

    private suspend fun searchDuckDuckGoHtml(query: String): List<WebLookupEntry> {
        val url = "https://duckduckgo.com/html/?q=${urlEncode(query)}"
        val response = httpClient.get(url, SEARCH_HEADERS, MAX_SEARCH_RESPONSE_BYTES)
        if (response.statusCode !in 200..299) return emptyList()
        val body = response.body.toString(Charsets.UTF_8)
        return DUCK_RESULT_LINK_REGEX.findAll(body)
            .mapNotNull { match ->
                val href = extractAttribute(match.groupValues[1], "href")
                    ?.let(::decodeHtml)
                    ?.let(::resolveDuckDuckGoRedirect)
                    ?.takeIf(::isPublicWebUrl)
                    ?: return@mapNotNull null
                val title = decodeHtml(stripTags(match.groupValues[2])).cleanResultText()
                if (title.isBlank()) return@mapNotNull null
                WebLookupEntry(title = shortSourceLabel(title), url = href, snippet = "")
            }
            .distinctBy { it.url }
            .take(MAX_RESULTS)
            .toList()
    }

    private suspend fun searchWikipedia(query: String): List<WebLookupEntry> {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json" +
            "&srlimit=$MAX_RESULTS&srsearch=${urlEncode(query)}"
        val response = httpClient.get(url, SEARCH_HEADERS, MAX_SEARCH_RESPONSE_BYTES)
        if (response.statusCode !in 200..299) return emptyList()
        val search = JSONObject(response.body.toString(Charsets.UTF_8))
            .optJSONObject("query")
            ?.optJSONArray("search")
            ?: return emptyList()
        return buildList {
            for (index in 0 until search.length()) {
                val title = search.optJSONObject(index)?.optString("title")?.trim().orEmpty()
                if (title.isNotBlank()) {
                    add(
                        WebLookupEntry(
                            title = shortSourceLabel(title),
                            url = "https://en.wikipedia.org/wiki/${urlEncodePath(title.replace(' ', '_'))}",
                            snippet = "",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun crawlWithCrawl4Ai(
        query: String,
        discovered: List<WebLookupEntry>,
    ): WebLookupResult {
        val payload = JSONObject().apply {
            put("urls", JSONArray(discovered.map { it.url }))
            put(
                "browser_config",
                JSONObject().apply {
                    put("type", "BrowserConfig")
                    put("params", JSONObject().apply { put("headless", true) })
                },
            )
            put(
                "crawler_config",
                JSONObject().apply {
                    put("type", "CrawlerRunConfig")
                    put(
                        "params",
                        JSONObject().apply {
                            put("cache_mode", "bypass")
                            put("word_count_threshold", 10)
                            put("page_timeout", CRAWL_PAGE_TIMEOUT_MS)
                            put("excluded_tags", JSONArray(listOf("nav", "footer", "aside")))
                        },
                    )
                },
            )
        }
        val headers = buildMap {
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            crawl4AiConfig.apiToken.takeIf { it.isNotBlank() }?.let {
                put("Authorization", "Bearer $it")
            }
        }
        val response = runCatching {
            httpClient.post(
                url = "${crawl4AiConfig.baseUrl.trimEnd('/')}/crawl",
                body = payload.toString().toByteArray(Charsets.UTF_8),
                headers = headers,
                maxBytes = MAX_CRAWL_RESPONSE_BYTES,
            )
        }.getOrElse { failure ->
            return WebLookupResult(
                query = query,
                results = emptyList(),
                error = "Crawl4AI could not be reached: ${failure.message.orEmpty()}",
                errorKind = WebLookupErrorKind.RESEARCH_UNAVAILABLE,
            )
        }
        if (response.statusCode !in 200..299) {
            return WebLookupResult(
                query = query,
                results = emptyList(),
                error = "Crawl4AI returned HTTP ${response.statusCode}.",
                errorKind = WebLookupErrorKind.RESEARCH_UNAVAILABLE,
            )
        }

        val root = runCatching { JSONObject(response.body.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return WebLookupResult(
                query,
                emptyList(),
                "Crawl4AI returned an invalid response.",
                WebLookupErrorKind.RESEARCH_UNAVAILABLE,
            )
        val crawled = root.optJSONArray("results") ?: JSONArray()
        val sourceByUrl = discovered.associateBy { it.url.trimEnd('/') }
        val results = buildList {
            for (index in 0 until crawled.length()) {
                val item = crawled.optJSONObject(index) ?: continue
                if (!item.optBoolean("success", true)) continue
                val url = item.optString("url").trim()
                if (!isPublicWebUrl(url)) continue
                val markdown = item.extractMarkdown()
                if (markdown.isBlank()) continue
                val source = sourceByUrl[url.trimEnd('/')]
                add(
                    WebLookupEntry(
                        title = source?.title ?: sourceLabel(url),
                        url = url,
                        snippet = relevantExcerpt(markdown, query),
                    ),
                )
            }
        }.distinctBy { it.url }.take(MAX_RESULTS)
        return if (results.isEmpty()) {
            WebLookupResult(
                query,
                emptyList(),
                "Crawl4AI could not extract readable content from the discovered pages.",
                WebLookupErrorKind.NO_RESULTS,
            )
        } else {
            WebLookupResult(query = query, results = results)
        }
    }

    internal interface HttpClient {
        suspend fun get(
            url: String,
            headers: Map<String, String> = emptyMap(),
            maxBytes: Int,
        ): WebLookupHttpResponse

        suspend fun post(
            url: String,
            body: ByteArray,
            headers: Map<String, String> = emptyMap(),
            maxBytes: Int,
        ): WebLookupHttpResponse = throw IOException("POST is not implemented")
    }

    private class OkHttpWebClient : HttpClient {
        private val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            maxBytes: Int,
        ): WebLookupHttpResponse = execute(
            Request.Builder().url(url).withHeaders(headers).get().build(),
            maxBytes,
        )

        override suspend fun post(
            url: String,
            body: ByteArray,
            headers: Map<String, String>,
            maxBytes: Int,
        ): WebLookupHttpResponse = execute(
            Request.Builder()
                .url(url)
                .withHeaders(headers)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            maxBytes,
        )

        private suspend fun execute(request: Request, maxBytes: Int): WebLookupHttpResponse =
            suspendCancellableCoroutine { continuation ->
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
                                    WebLookupHttpResponse(it.code, bytes)
                                }
                            }
                            if (!continuation.isActive) return
                            result.fold(
                                onSuccess = continuation::resume,
                                onFailure = { continuation.resumeWith(Result.failure(it)) },
                            )
                        }
                    },
                )
            }

        private fun Request.Builder.withHeaders(headers: Map<String, String>): Request.Builder = apply {
            headers.forEach { (name, value) -> header(name, value) }
        }
    }

    companion object {
        const val INTERNET_REQUIRED_MESSAGE =
            "This question requires internet research to answer properly. " +
                "Connect to the internet and make sure the Crawl4AI research service is available, then try again."
        private const val MAX_RESULTS = 4
        private const val MAX_SEARCH_RESPONSE_BYTES = 512 * 1024
        private const val MAX_CRAWL_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val CRAWL_PAGE_TIMEOUT_MS = 15_000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SEARCH_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Android) StarbuckNoteTaker/1.0",
            "Accept" to "text/html,application/json",
        )
        private val DUCK_RESULT_LINK_REGEX = Regex(
            """(<a\b[^>]*class=['"][^'"]*result__a[^'"]*['"][^>]*>)(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val TAG_REGEX = Regex("<[^>]+>")
        private val HTML_ENTITY_REGEX = Regex("&(#(?:x[0-9a-fA-F]+|[0-9]+)|[a-zA-Z]+);")
        private val HTML_ENTITIES = mapOf(
            "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
            "nbsp" to " ", "ndash" to "–", "mdash" to "—",
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

        private fun normalizeQuery(question: String): String = question
            .replace(EXPLICIT_LOOKUP_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { question.trim() }

        private fun relevantExcerpt(markdown: String, query: String): String {
            val terms = query.lowercase(Locale.US)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 3 }
                .toSet()
            val paragraphs = markdown
                .replace(Regex("```[\\s\\S]*?```"), " ")
                .lines()
                .map { line -> line.replace(Regex("^[#>*_`\\-\\d.\\s]+"), "").trim() }
                .filter { it.length >= 40 }
            return paragraphs
                .sortedByDescending { paragraph ->
                    val lower = paragraph.lowercase(Locale.US)
                    terms.count(lower::contains)
                }
                .take(3)
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .take(1_200)
                .trim()
        }

        private fun JSONObject.extractMarkdown(): String = when (val value = opt("markdown")) {
            is JSONObject -> value.optString("fit_markdown")
                .ifBlank { value.optString("raw_markdown") }
                .ifBlank { value.optString("markdown_with_citations") }
            is String -> value
            else -> ""
        }

        private fun stripTags(value: String): String = TAG_REGEX.replace(value, " ")

        private fun decodeHtml(value: String): String = HTML_ENTITY_REGEX.replace(value) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x", ignoreCase = true) ->
                    entity.substring(2).toIntOrNull(16)?.toUnicodeString()
                entity.startsWith('#') -> entity.substring(1).toIntOrNull()?.toUnicodeString()
                else -> HTML_ENTITIES[entity.lowercase(Locale.US)]
            } ?: match.value
        }

        private fun Int.toUnicodeString(): String? =
            takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }

        private fun String.cleanResultText(): String = replace(Regex("\\s+"), " ").trim()

        private fun extractAttribute(tag: String, name: String): String? =
            Regex("""\b$name\s*=\s*(['"])(.*?)\1""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.getOrNull(2)

        private fun resolveDuckDuckGoRedirect(rawUrl: String): String {
            if (!rawUrl.contains("uddg=")) return rawUrl
            val encoded = rawUrl.substringAfter("uddg=").substringBefore('&')
            return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }
                .getOrElse { rawUrl }
        }

        private fun isPublicWebUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            val host = uri.host?.lowercase(Locale.US).orEmpty()
            (uri.scheme == "https" || uri.scheme == "http") && host.isNotBlank() &&
                host != "localhost" && host != "127.0.0.1" && host != "0.0.0.0" &&
                !host.startsWith("10.") && !host.startsWith("192.168.") &&
                !Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
        }.getOrDefault(false)

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
    }
}

internal fun interface WebResearcher {
    suspend fun lookup(question: String): WebLookupResult
}

internal data class Crawl4AiConfig(
    val baseUrl: String,
    val apiToken: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.startsWith("https://") || baseUrl.startsWith("http://")

    companion object {
        fun fromBuildConfig(): Crawl4AiConfig = Crawl4AiConfig(
            baseUrl = BuildConfig.CRAWL4AI_BASE_URL.trim(),
            apiToken = BuildConfig.CRAWL4AI_API_TOKEN.trim(),
        )
    }
}

internal data class WebLookupHttpResponse(val statusCode: Int, val body: ByteArray)

internal data class WebLookupEntry(val title: String, val url: String, val snippet: String) {
    fun markdownLink(): String = "[${title.take(48).ifBlank { "Source" }}]($url)"
}

internal enum class WebLookupErrorKind { OFFLINE, RESEARCH_UNAVAILABLE, NO_RESULTS }

internal data class WebLookupResult(
    val query: String,
    val results: List<WebLookupEntry>,
    val error: String? = null,
    val errorKind: WebLookupErrorKind? = null,
) {
    fun toPromptContext(): String = buildString {
        appendLine("Crawl4AI web research for: $query")
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
