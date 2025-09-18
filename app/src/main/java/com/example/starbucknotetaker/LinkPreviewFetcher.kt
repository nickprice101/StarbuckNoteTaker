package com.example.starbucknotetaker

import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_HTML_CHARS = 200_000

class LinkPreviewFetcher {
    private val cache = mutableMapOf<String, LinkPreviewResult>()

    suspend fun fetch(rawUrl: String): LinkPreviewResult {
        val normalized = normalizeUrl(rawUrl)
        cache[normalized]?.let { return it }
        val result = withContext(Dispatchers.IO) {
            runCatching { loadPreview(normalized) }.getOrElse {
                LinkPreviewResult.Failure(it.message)
            }
        }
        cache[normalized] = result
        return result
    }

    private fun loadPreview(urlString: String): LinkPreviewResult {
        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Android) StarbuckNoteTaker/1.0"
            )
        }
        return try {
            connection.inputStream.use { input ->
                val reader = InputStreamReader(input, Charsets.UTF_8)
                val html = reader.readLimited(MAX_HTML_CHARS)
                val meta = parseMetadata(html, url)
                val title = meta.title ?: url.host
                val description = meta.description
                val imageUrl = meta.imageUrl
                val preview = NoteLinkPreview(
                    url = urlString,
                    title = title,
                    description = description,
                    imageUrl = imageUrl
                )
                LinkPreviewResult.Success(preview)
            }
        } finally {
            connection.disconnect()
        }
    }
}

private fun InputStreamReader.readLimited(maxChars: Int): String {
    val builder = StringBuilder()
    val buffer = CharArray(4096)
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        val remaining = maxChars - builder.length
        if (remaining <= 0) break
        val toAppend = if (read > remaining) remaining else read
        builder.append(buffer, 0, toAppend)
        if (builder.length >= maxChars) break
    }
    return builder.toString()
}

data class ParsedLinkMetadata(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
)

private fun parseMetadata(html: String, baseUrl: URL): ParsedLinkMetadata {
    val lowerHtml = html.lowercase(Locale.ROOT)
    val title = extractTagContent(html, "title")?.cleanWhitespace()
        ?: extractMetaContent(lowerHtml, html, "property", "og:title")?.cleanWhitespace()
    val description =
        extractMetaContent(lowerHtml, html, "property", "og:description")?.cleanWhitespace()
            ?: extractMetaContent(lowerHtml, html, "name", "description")?.cleanWhitespace()
    val image = extractMetaContent(lowerHtml, html, "property", "og:image")?.let { resolveUrl(baseUrl, it) }
    return ParsedLinkMetadata(title, description, image)
}

private fun extractTagContent(html: String, tag: String): String? {
    val regex = Regex(
        pattern = "<${tag}[^>]*>(.*?)</${tag}>",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    val match = regex.find(html) ?: return null
    return match.groupValues.getOrNull(1)?.cleanWhitespace()
}

private fun extractMetaContent(
    lowerHtml: String,
    originalHtml: String,
    attribute: String,
    value: String,
): String? {
    val attrRegex = Regex("<meta[^>]*$attribute\\s*=\\s*['\"]?$value['\"]?[^>]*>", RegexOption.IGNORE_CASE)
    val match = attrRegex.find(lowerHtml) ?: return null
    val startIndex = match.range.first
    val endIndex = match.range.last
    val snippet = originalHtml.substring(startIndex, endIndex + 1)
    val contentRegex = Regex("content\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
    val contentMatch = contentRegex.find(snippet)
    return contentMatch?.groupValues?.getOrNull(1)
}

private fun resolveUrl(base: URL, candidate: String): String? {
    return runCatching { URL(base, candidate).toString() }.getOrNull()
}

private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim()
    return if (schemeRegex.containsMatchIn(trimmed)) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

sealed class LinkPreviewResult {
    data class Success(val preview: NoteLinkPreview) : LinkPreviewResult()
    data class Failure(val message: String? = null) : LinkPreviewResult()
}

fun extractUrls(text: String): List<String> {
    val matcher = android.util.Patterns.WEB_URL.matcher(text)
    val results = mutableListOf<String>()
    while (matcher.find()) {
        var url = text.substring(matcher.start(), matcher.end())
        url = url.trimEnd('.', ',', ';', ')', ']', '}', '>', '"', '\'')
        if (url.isNotBlank()) {
            results.add(url)
        }
    }
    return results.distinct()
}

private fun String.cleanWhitespace(): String {
    return trim().replace(Regex("\\s+"), " ")
}
