package com.example.starbucknotetaker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal class AssistantWebLookup(
    private val httpClient: HttpClient = HttpUrlConnectionClient(),
) {
    suspend fun lookup(question: String): WebLookupResult = withContext(Dispatchers.IO) {
        val query = normalizeQuery(question)
        if (query.isBlank()) {
            return@withContext WebLookupResult(question, emptyList(), "empty query")
        }

        // Query providers together so an unreachable endpoint cannot multiply the wait.
        // Return as soon as any provider succeeds instead of waiting for every request.
        val answer = supervisorScope {
            val attempts = listOf(
                async { lookupSafely(query, "DuckDuckGo HTML", ::searchDuckDuckGoHtml) },
                async {
                    lookupSafely(
                        query,
                        "DuckDuckGo instant answer",
                        ::searchDuckDuckGoInstantAnswer,
                    )
                },
                async { lookupSafely(query, "Wikipedia", ::searchWikipedia) },
            ).toMutableList()
            val failures = mutableListOf<WebLookupResult>()
            while (attempts.isNotEmpty()) {
                val (completed, result) = select<Pair<Deferred<WebLookupResult>, WebLookupResult>> {
                    attempts.forEach { attempt ->
                        attempt.onAwait { attempt to it }
                    }
                }
                attempts.remove(completed)
                if (result.results.isNotEmpty()) {
                    attempts.forEach { it.cancel() }
                    return@supervisorScope result
                }
                failures += result
            }
            WebLookupResult(
                query,
                emptyList(),
                failures.mapNotNull { it.error }.firstOrNull() ?: "No web results found",
            )
        }
        answer
    }

    private suspend fun lookupSafely(
        query: String,
        provider: String,
        lookup: suspend (String) -> WebLookupResult,
    ): WebLookupResult =
        runCatching { lookup(query) }
            .getOrElse { WebLookupResult(query, emptyList(), "$provider unavailable: ${it.message.orEmpty()}") }

    private suspend fun searchDuckDuckGoHtml(query: String): WebLookupResult {
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

    private suspend fun searchDuckDuckGoInstantAnswer(query: String): WebLookupResult {
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

    private suspend fun searchWikipedia(query: String): WebLookupResult {
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
        suspend fun get(
            url: String,
            headers: Map<String, String> = emptyMap(),
            maxBytes: Int,
        ): WebLookupHttpResponse
    }

    private class HttpUrlConnectionClient : HttpClient {
        private val client = OkHttpClient.Builder()
            .connectTimeout(1_500, TimeUnit.MILLISECONDS)
            .readTimeout(2_500, TimeUnit.MILLISECONDS)
            .callTimeout(3_000, TimeUnit.MILLISECONDS)
            .build()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            maxBytes: Int,
        ): WebLookupHttpResponse = suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .apply {
                    headers.forEach { (name, value) -> header(name, value) }
                }
                .build()

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
                                val body = it.body?.byteStream()?.use { stream ->
                                    stream.readBytesLimited(maxBytes)
                                } ?: ByteArray(0)
                                WebLookupHttpResponse(it.code, body)
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
        private val HTML_ENTITY_REGEX = Regex("&(#(?:x[0-9a-fA-F]+|[0-9]+)|[a-zA-Z]+);")
        private val HTML_ENTITIES = mapOf(
            "amp" to "&",
            "quot" to "\"",
            "apos" to "'",
            "lt" to "<",
            "gt" to ">",
            "nbsp" to " ",
            "ndash" to "–",
            "mdash" to "—",
        )
        private val CURRENT_INFO_REGEX = Regex(
            "\\b(latest|current|currently|today|tonight|yesterday|tomorrow|news|weather|forecast|score|scores|schedule|price|stock|version|release|released|update|updates|recent|newest|now)\\b",
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
            "\\b(?:should|could|would|this\\s+note|current\\s+note|rewrite|reformat|format|summari[sz]e|brainstorm|draft|create)\\b",
            RegexOption.IGNORE_CASE,
        )

        fun shouldLookup(question: String): Boolean {
            val trimmed = question.trim()
            if (trimmed.isBlank()) return false
            if (extractUrls(trimmed, treatUnterminatedAsComplete = true).isNotEmpty()) return true
            return EXPLICIT_LOOKUP_REGEX.containsMatchIn(trimmed) ||
                CURRENT_INFO_REGEX.containsMatchIn(trimmed) ||
                (FACTUAL_QUESTION_REGEX.containsMatchIn(trimmed) &&
                    !NON_LOOKUP_QUESTION_REGEX.containsMatchIn(trimmed))
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
            val primary = results.first()
            return buildString {
                if (primary.snippet.isNotBlank()) {
                    appendLine(primary.snippet)
                } else {
                    appendLine(primary.title)
                }
                appendLine()
                appendLine("Sources:")
                results.take(3).forEachIndexed { index, result ->
                    appendLine("${index + 1}. ${result.title}: ${result.url}")
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
            HTML_ENTITY_REGEX.replace(value) { match ->
                val entity = match.groupValues[1]
                when {
                    entity.startsWith("#x", ignoreCase = true) ->
                        entity.substring(2).toIntOrNull(16)?.toUnicodeString()
                    entity.startsWith('#') ->
                        entity.substring(1).toIntOrNull()?.toUnicodeString()
                    else -> HTML_ENTITIES[entity.lowercase(Locale.US)]
                } ?: match.value
            }

        private fun Int.toUnicodeString(): String? =
            takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }

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
