package com.example.starbucknotetaker

import android.content.Context
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_HTML_CHARS = 200_000

class LinkPreviewFetcher(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "link_previews").apply { mkdirs() }
    private val cache = mutableMapOf<String, LinkPreviewResult>()

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
                LinkPreviewResult.Success(preview)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheImage(urlKey: String, remoteImageUrl: String): String? {
        val existing = findCachedFile(urlKey)
        if (existing != null && existing.exists()) {
            return existing.absolutePath
        }
        return runCatching {
            val connection = (URL(remoteImageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Android) StarbuckNoteTaker/1.0"
                )
            }
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    return@runCatching null
                }
                val cacheKey = cacheKeyFor(urlKey)
                val extension = extensionFromConnection(connection.contentType, remoteImageUrl)
                cacheDir.listFiles()
                    ?.filter { it.nameWithoutExtensionSafe() == cacheKey }
                    ?.forEach { it.delete() }
                val target = File(cacheDir, cacheKey + extension)
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                target.absolutePath
            } finally {
                connection.disconnect()
            }
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

    private fun extensionFromConnection(contentType: String?, remoteImageUrl: String): String {
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

private fun buildSnapshotUrl(urlString: String): String {
    val encoded = URLEncoder.encode(urlString, Charsets.UTF_8.name())
    return "https://s.wordpress.com/mshots/v1/$encoded?w=1200"
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

data class UrlDetection(
    val originalUrl: String,
    val normalizedUrl: String,
    val isComplete: Boolean,
)

fun extractUrls(text: String, treatUnterminatedAsComplete: Boolean = false): List<UrlDetection> {
    val matcher = android.util.Patterns.WEB_URL.matcher(text)
    val detections = linkedMapOf<String, UrlDetection>()
    while (matcher.find()) {
        val matchStart = matcher.start()
        var url = text.substring(matchStart, matcher.end())
        val trimmed = trimTrailingSentencePunctuation(url)
        if (trimmed.isNotBlank()) {
            val trimmedTrailing = trimmed.length != url.length
            val nextIndex = matchStart + trimmed.length
            val nextChar = text.getOrNull(nextIndex)
            val isComplete = when {
                trimmedTrailing -> true
                nextChar == null -> treatUnterminatedAsComplete
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
