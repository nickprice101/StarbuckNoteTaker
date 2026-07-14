package com.example.starbucknotetaker

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class AssistantWebLookup(
    private val httpClient: HttpClient = HttpUrlConnectionClient(),
) {
    suspend fun lookup(question: String): WebLookupResult = withContext(Dispatchers.IO) {
        val query = normalizeQuery(question)
        if (query.isBlank()) {
            return@withContext WebLookupResult(question, emptyList(), "empty query")
        }

        val htmlResult = runCatching { searchDuckDuckGoHtml(query) }.getOrNull()
        if (htmlResult != null && htmlResult.results.isNotEmpty()) {
            return@withContext htmlResult
        }

        val instantResult = runCatching { searchDuckDuckGoInstantAnswer(query) }.getOrNull()
        if (instantResult != null && instantResult.results.isNotEmpty()) {
            return@withContext instantResult
        }

        val wikiResult = runCatching { searchWikipedia(query) }.getOrNull()
        if (wikiResult != null && wikiResult.results.isNotEmpty()) {
            return@withContext wikiResult
        }

        WebLookupResult(
            query = query,
            results = emptyList(),
            error = htmlResult?.error ?: instantResult?.error ?: wikiResult?.error ?: "No web results found",
        )
    }

    private fun searchDuckDuckGoHtml(query: String): WebLookupResult {
        val url = "https://duckduckgo.com/html/?q=${urlEncode(query)}"
        val response = httpClient.get(url, SEARCH_HEADERS, MAX_SEARCH_RESPONSE_BYTES)
        if (response.statusCode !in 200..299) {
            return WebLookupResult(query, emptyList(), "DuckDuckGo returned ${response.statusCode}")
        }

        val body = response.body.toString(Charsets.UTF_8)
        val results = DUCK_RESULT_LINK_REGEX.findAll(body)
            .mapNotNull { match ->
                val fullTag = match.groupValues[1]
                val href = extractAttribute(fullTag, "href")
                    ?.let(::decodeHtml)
                    ?.let(::resolveDuckDuckGoRedirect)
                    ?: return@mapNotNull null
                val title = decodeHtml(stripTags(match.groupValues[2])).cleanResultText()
                if (title.isBlank()) return@mapNotNull null
                val blockStart = match.range.last + 1
                val nextStart = DUCK_RESULT_LINK_REGEX.find(body, blockStart)?.range?.first ?: body.length
                val block = body.substring(blockStart, nextStart.coerceAtMost(body.length))
                val snippet = DUCK_SNIPPET_REGEX.find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { decodeHtml(stripTags(it)).cleanResultText() }
                    .orEmpty()
                WebLookupEntry(title = title, url = href, snippet = snippet)
            }
            .distinctBy { it.url }
            .take(MAX_RESULTS)
            .toList()

        return WebLookupResult(query, results, if (results.isEmpty()) "No DuckDuckGo results parsed" else null)
    }

    private fun searchDuckDuckGoInstantAnswer(query: String): WebLookupResult {
        val url = "https://api.duckduckgo.com/?q=${urlEncode(query)}&format=json&no_html=1&skip_disambig=1"
        val response = httpClient.get(url, SEARCH_HEADERS, MAX_SEARCH_RESPONSE_BYTES)
        if (response.statusCode !in 200..299) {
            return WebLookupResult(query, emptyList(), "DuckDuckGo instant answer returned ${response.statusCode}")
        }

        val json = JSONObject(response.body.toString(Charsets.UTF_8))
        val results = mutableListOf<WebLookupEntry>()
        val abstractText = json.optString("AbstractText").trim()
        if (abstractText.isNotEmpty()) {
            results += WebLookupEntry(
                title = json.optString("Heading").ifBlank { query },
                url = json.optString("AbstractURL").ifBlank { "https://duckduckgo.com/?q=${urlEncode(query)}" },
                snippet = abstractText,
            )
        }

        fun addRelated(topic: JSONObject) {
            val text = topic.optString("Text").trim()
            val firstUrl = topic.optString("FirstURL").trim()
            if (text.isNotEmpty() && firstUrl.isNotEmpty()) {
                val title = text.substringBefore(" - ").ifBlank { query }
                results += WebLookupEntry(title = title, url = firstUrl, snippet = text)
            }
        }

        val related = json.optJSONArray("RelatedTopics")
        if (related != null) {
            for (i in 0 until related.length()) {
                val item = related.optJSONObject(i) ?: continue
                val nested = item.optJSONArray("Topics")
                if (nested == null) {
                    addRelated(item)
                } else {
                    for (j in 0 until nested.length()) {
                        nested.optJSONObject(j)?.let(::addRelated)
                    }
                }
                if (results.size >= MAX_RESULTS) break
            }
        }

        return WebLookupResult(
            query = query,
            results = results.distinctBy { it.url }.take(MAX_RESULTS),
            error = if (results.isEmpty()) "No DuckDuckGo instant answer found" else null,
        )
    }

    private fun searchWikipedia(query: String): WebLookupResult {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=$MAX_RESULTS&srsearch=${urlEncode(query)}"
        val response = httpClient.get(url, SEARCH_HEADERS, MAX_SEARCH_RESPONSE_BYTES)
        if (response.statusCode !in 200..299) {
            return WebLookupResult(query, emptyList(), "Wikipedia returned ${response.statusCode}")
        }

        val search = JSONObject(response.body.toString(Charsets.UTF_8))
            .optJSONObject("query")
            ?.optJSONArray("search")
            ?: return WebLookupResult(query, emptyList(), "No Wikipedia search results")

        val results = buildList {
            for (i in 0 until search.length()) {
                val item = search.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                val snippet = decodeHtml(stripTags(item.optString("snippet"))).cleanResultText()
                add(
                    WebLookupEntry(
                        title = title,
                        url = "https://en.wikipedia.org/wiki/${urlEncodePath(title.replace(' ', '_'))}",
                        snippet = snippet,
                    )
                )
            }
        }

        return WebLookupResult(
            query = query,
            results = results,
            error = if (results.isEmpty()) "No Wikipedia search results" else null,
        )
    }

    internal interface HttpClient {
        fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int): WebLookupHttpResponse
    }

    private class HttpUrlConnectionClient : HttpClient {
        private val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        override fun get(
            url: String,
            headers: Map<String, String>,
            maxBytes: Int,
        ): WebLookupHttpResponse {
            val request = Request.Builder()
                .url(url)
                .apply {
                    headers.forEach { (name, value) -> header(name, value) }
                }
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.byteStream()?.use { it.readBytesLimited(maxBytes) }
                    ?: ByteArray(0)
                return WebLookupHttpResponse(response.code, body)
            }
        }
    }

    companion object {
        private const val MAX_RESULTS = 4
        private const val MAX_SEARCH_RESPONSE_BYTES = 512 * 1024
        private val SEARCH_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Android) StarbuckNoteTaker/1.0",
            "Accept" to "text/html,application/json",
        )
        private val DUCK_RESULT_LINK_REGEX = Regex(
            """(<a\b[^>]*class=['"][^'"]*result__a[^'"]*['"][^>]*>)(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val DUCK_SNIPPET_REGEX = Regex(
            """class=['"][^'"]*result__snippet[^'"]*['"][^>]*>(.*?)</[^>]+>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val TAG_REGEX = Regex("<[^>]+>")
        private val CURRENT_INFO_REGEX = Regex(
            "\\b(latest|current|currently|today|tonight|yesterday|tomorrow|news|weather|forecast|score|scores|schedule|price|stock|version|release|released|update|updates|recent|newest|now)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val EXPLICIT_LOOKUP_REGEX = Regex(
            "\\b(search|web|internet|online|look\\s*up|lookup|google|source|sources|cite|citation)\\b",
            RegexOption.IGNORE_CASE,
        )

        fun shouldLookup(question: String): Boolean {
            val trimmed = question.trim()
            if (trimmed.isBlank()) return false
            if (extractUrls(trimmed, treatUnterminatedAsComplete = true).isNotEmpty()) return true
            return EXPLICIT_LOOKUP_REGEX.containsMatchIn(trimmed) ||
                CURRENT_INFO_REGEX.containsMatchIn(trimmed)
        }

        fun mergeWithNoteContext(noteContext: String?, webLookup: WebLookupResult): String =
            buildString {
                val note = noteContext?.trim().orEmpty()
                if (note.isNotEmpty()) {
                    appendLine("Note context:")
                    appendLine(note)
                    appendLine()
                }
                append(webLookup.toPromptContext())
            }.trim()

        fun quickAnswer(question: String, webLookup: WebLookupResult): String {
            val results = webLookup.results
            if (results.isEmpty()) {
                return "I could not find web results quickly enough for: ${question.trim()}"
            }
            return buildString {
                appendLine("I found these web results:")
                appendLine()
                results.take(3).forEachIndexed { index, result ->
                    appendLine("${index + 1}. ${result.title}")
                    if (result.snippet.isNotBlank()) {
                        appendLine(result.snippet)
                    }
                    appendLine(result.url)
                    if (index != results.take(3).lastIndex) appendLine()
                }
            }.trim()
        }

        private fun normalizeQuery(question: String): String =
            question
                .replace(EXPLICIT_LOOKUP_REGEX, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { question.trim() }

        private fun stripTags(value: String): String = TAG_REGEX.replace(value, " ")

        private fun decodeHtml(value: String): String =
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()

        private fun String.cleanResultText(): String =
            replace(Regex("\\s+"), " ").trim()

        private fun extractAttribute(tag: String, name: String): String? {
            val regex = Regex("""\b$name\s*=\s*(['"])(.*?)\1""", RegexOption.IGNORE_CASE)
            return regex.find(tag)?.groupValues?.getOrNull(2)
        }

        private fun resolveDuckDuckGoRedirect(rawUrl: String): String {
            if (!rawUrl.contains("uddg=")) return rawUrl
            val encoded = rawUrl.substringAfter("uddg=").substringBefore('&')
            return runCatching {
                URLDecoder.decode(encoded, Charsets.UTF_8.name())
            }.getOrElse { rawUrl }
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun urlEncodePath(value: String): String =
            value.split('/').joinToString("/") { urlEncode(it).replace("+", "%20") }
    }
}

internal data class WebLookupHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

internal data class WebLookupEntry(
    val title: String,
    val url: String,
    val snippet: String,
)

internal data class WebLookupResult(
    val query: String,
    val results: List<WebLookupEntry>,
    val error: String? = null,
) {
    fun toPromptContext(): String = buildString {
        appendLine("Web lookup for: $query")
        if (results.isEmpty()) {
            appendLine("No web results were available.")
            error?.let { appendLine("Lookup error: $it") }
            return@buildString
        }
        appendLine("Use these sources when they are relevant. Cite URLs in the answer.")
        results.forEachIndexed { index, result ->
            appendLine()
            appendLine("Source ${index + 1}: ${result.title}")
            appendLine("URL: ${result.url}")
            if (result.snippet.isNotBlank()) {
                appendLine("Snippet: ${result.snippet}")
            }
        }
    }.trim()

    fun progressPreview(): String =
        results.take(2).joinToString("\n") { result ->
            if (result.snippet.isBlank()) {
                "${result.title} - ${result.url}"
            } else {
                "${result.title}: ${result.snippet}"
            }
        }.take(500)
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
        if (toWrite < read || total >= maxBytes) {
            break
        }
    }
    return buffer.toByteArray()
}
