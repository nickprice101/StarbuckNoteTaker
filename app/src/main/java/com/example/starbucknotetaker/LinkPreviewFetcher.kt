package com.example.starbucknotetaker

import android.content.Context
import android.net.Uri
import androidx.core.util.PatternsCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_HTML_CHARS = 200_000

class LinkPreviewFetcher(
    context: Context,
    private val httpClient: LinkPreviewHttpClient = LinkPreviewHttpClient.HttpUrlConnection(),
    cacheDirFactory: (Context) -> File = { ctx ->
        File(ctx.filesDir, "link_previews").apply { mkdirs() }
    }
) {
    private val appContext = context.applicationContext
    private val cacheDir = cacheDirFactory(appContext)
    private val cache = mutableMapOf<String, LinkPreviewResult>()
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Android) StarbuckNoteTaker/1.0"
    )

    suspend fun fetch(rawUrl: String): LinkPreviewResult {
        val normalized = normalizeUrl(rawUrl)
        cache[normalized]?.let { cached ->
            if (cached is LinkPreviewResult.Success) {
                val path = cached.preview.cachedImagePath
                if (path == null || File(path).exists()) {
                    return cached
                }
                cache.remove(normalized)
            } else {
                return cached
            }
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { loadPreview(normalized) }
                .getOrElse { LinkPreviewResult.Failure(it.message) }
        }
        cache[normalized] = result
        return result
    }

    private fun loadPreview(urlString: String): LinkPreviewResult {
        val url = URL(urlString)
        val response = httpClient.get(urlString, defaultHeaders, MAX_HTML_CHARS * 4)
        if (response.statusCode !in 200..299) {
            return LinkPreviewResult.Failure("Failed to load preview (${response.statusCode})")
        }
        val html = response.body.toString(Charsets.UTF_8).take(MAX_HTML_CHARS)
        val meta = parseMetadata(html, url)
        val title = meta.title ?: url.host
        val description = meta.description
        val imageCandidates = buildList {
            meta.imageUrl?.let { add(it) }
            add(buildSnapshotUrl(urlString))
        }
        var selectedImageUrl: String? = null
        var cachedImagePath: String? = null
        for (candidate in imageCandidates) {
            selectedImageUrl = candidate
            val cached = cacheImage(urlString, candidate)
            if (cached != null) {
                cachedImagePath = cached
                break
            }
        }
        val preview = NoteLinkPreview(
            url = urlString,
            title = title,
            description = description,
            imageUrl = selectedImageUrl,
            cachedImagePath = cachedImagePath,
        )
        return LinkPreviewResult.Success(preview)
    }

    private fun cacheImage(urlKey: String, remoteImageUrl: String): String? {
        val existing = findCachedFile(urlKey)
        if (existing != null && existing.exists()) {
            return existing.absolutePath
        }
        return runCatching {
            val response = httpClient.get(remoteImageUrl, defaultHeaders)
            if (response.statusCode !in 200..299 || response.body.isEmpty()) {
                return@runCatching null
            }
            val cacheKey = cacheKeyFor(urlKey)
            val extension = extensionFromContent(response.contentType, remoteImageUrl)
            cacheDir.listFiles()
                ?.filter { it.nameWithoutExtensionSafe() == cacheKey }
                ?.forEach { it.delete() }
            val target = File(cacheDir, cacheKey + extension)
            target.outputStream().use { output ->
                output.write(response.body)
            }
            target.absolutePath
        }.getOrElse { null }
    }

    private fun findCachedFile(urlKey: String): File? {
        val cacheKey = cacheKeyFor(urlKey)
        return cacheDir.listFiles()?.firstOrNull { it.nameWithoutExtensionSafe() == cacheKey }
    }

    private fun cacheKeyFor(urlKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(urlKey.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun extensionFromContent(contentType: String?, remoteImageUrl: String): String {
        val normalized = contentType?.lowercase(Locale.ROOT) ?: ""
        return when {
            "png" in normalized -> ".png"
            "gif" in normalized -> ".gif"
            "webp" in normalized -> ".webp"
            "bmp" in normalized -> ".bmp"
            "svg" in normalized -> ".svg"
            else -> {
                val cleanedPath = remoteImageUrl.substringBefore('?').substringAfterLast('/')
                val ext = cleanedPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) {
                    ".${ext}"
                } else {
                    ".jpg"
                }
            }
        }
    }

    private fun File.nameWithoutExtensionSafe(): String {
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex <= 0) name else name.substring(0, dotIndex)
    }
}

interface LinkPreviewHttpClient {
    fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int? = null): LinkPreviewHttpResponse

    class HttpUrlConnection : LinkPreviewHttpClient {
        override fun get(
            url: String,
            headers: Map<String, String>,
            maxBytes: Int?
        ): LinkPreviewHttpResponse {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            return try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    if (maxBytes != null) input.readBytesLimited(maxBytes) else input.readBytes()
                } ?: ByteArray(0)
                LinkPreviewHttpResponse(status, connection.contentType, body)
            } finally {
                connection.disconnect()
            }
        }
    }
}

data class LinkPreviewHttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
)

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

private fun buildSnapshotUrl(urlString: String): String {
    val encoded = URLEncoder.encode(urlString, Charsets.UTF_8.name())
    return "https://s.wordpress.com/mshots/v1/$encoded?w=1200"
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
private val fallbackHttpUrlRegex = Regex("(?i)https?://[^\\s]+")

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

data class UrlDetection(
    val originalUrl: String,
    val normalizedUrl: String,
    val isComplete: Boolean,
)

fun extractUrls(text: String, treatUnterminatedAsComplete: Boolean = false): List<UrlDetection> {
    val detections = linkedMapOf<String, UrlDetection>()

    fun registerMatch(raw: String, matchStart: Int) {
        val trimmed = trimTrailingSentencePunctuation(raw)
        if (trimmed.isBlank()) return
        val trimmedTrailing = trimmed.length != raw.length
        val nextIndex = matchStart + trimmed.length
        val nextChar = text.getOrNull(nextIndex)
        val isComplete = when {
            trimmedTrailing -> true
            nextChar == null -> treatUnterminatedAsComplete || looksCompleteUrl(trimmed)
            nextChar.isWhitespace() -> true
            else -> false
        }
        val normalized = normalizeUrl(trimmed)
        val existing = detections[normalized]
        if (existing == null || (!existing.isComplete && isComplete)) {
            detections[normalized] = UrlDetection(
                originalUrl = trimmed,
                normalizedUrl = normalized,
                isComplete = isComplete || (existing?.isComplete == true),
            )
        }
    }

    val strictMatcher = android.util.Patterns.WEB_URL.matcher(text)
    while (strictMatcher.find()) {
        val matchStart = strictMatcher.start()
        val match = text.substring(matchStart, strictMatcher.end())
        registerMatch(match, matchStart)
    }

    fallbackHttpUrlRegex.findAll(text).forEach { result ->
        registerMatch(result.value, result.range.first)
    }

    return detections.values.toList()
}

private fun trimTrailingSentencePunctuation(url: String): String {
    var endIndex = url.length
    while (endIndex > 0) {
        val ch = url[endIndex - 1]
        val shouldTrim = when (ch) {
            '.', ',', ';', ')', ']', '}', '>', '\"', '\'' -> true
            else -> false
        }
        if (!shouldTrim) break
        val candidate = url.substring(0, endIndex - 1)
        if (ch == '.') {
            if (!android.util.Patterns.WEB_URL.matcher(candidate).matches()) {
                break
            }
        }
        endIndex--
    }
    return url.substring(0, endIndex)
}

private fun String.cleanWhitespace(): String {
    return trim().replace(Regex("\\s+"), " ")
}

private fun looksCompleteUrl(url: String): Boolean {
    val normalized = normalizeUrl(url)
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return false
    val host = uri.host ?: return false
    if (host.isBlank()) return false
    if (host.equals("localhost", ignoreCase = true)) return true
    if (host.contains('.')) return true
    if (PatternsCompat.IP_ADDRESS.matcher(host).matches()) return true
    if (uri.port != -1) return true
    val path = uri.path
    if (!path.isNullOrBlank() && path != "/") return true
    val query = uri.query
    if (!query.isNullOrBlank()) return true
    val fragment = uri.fragment
    if (!fragment.isNullOrBlank()) return true
    return false
}
