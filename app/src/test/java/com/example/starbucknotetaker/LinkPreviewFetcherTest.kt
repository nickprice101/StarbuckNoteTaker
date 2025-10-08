package com.example.starbucknotetaker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinkPreviewFetcherTest {

    private lateinit var context: Context
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        filesDir = File(context.filesDir, "link-preview-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::filesDir.isInitialized) {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun extractUrls_marksTerminalUrlComplete_whenLooksComplete() {
        val detections = extractUrls("https://example.com")

        assertEquals(1, detections.size)
        assertTrue(detections[0].isComplete)
    }

    @Test
    fun extractUrls_doesNotPrematurelyCompletePartialUrl() {
        val detections = extractUrls("https://example")

        assertEquals(1, detections.size)
        assertFalse(detections[0].isComplete)
    }

    @Test
    fun extractUrls_marksLocalhostWithPortComplete() {
        val detections = extractUrls("Visit http://localhost:3000")

        assertEquals(1, detections.size)
        assertTrue(detections[0].isComplete)
    }

    @Test
    fun fetch_usesInjectedHttpClientWithoutNetwork() = runBlocking {
        val html = """
            <html>
            <head>
                <title>Example Domain</title>
                <meta property="og:description" content="An example description" />
                <meta property="og:image" content="https://example.com/cover.png" />
            </head>
            <body>Hello</body>
            </html>
        """.trimIndent()
        val snapshotUrl = "https://s.wordpress.com/mshots/v1/" +
            URLEncoder.encode("https://example.com", StandardCharsets.UTF_8.name()) +
            "?w=1200"
        val responses = mapOf(
            "https://example.com" to LinkPreviewHttpResponse(
                statusCode = 200,
                contentType = "text/html",
                body = html.toByteArray()
            ),
            "https://example.com/cover.png" to LinkPreviewHttpResponse(
                statusCode = 200,
                contentType = "image/png",
                body = byteArrayOf(0x1, 0x2, 0x3)
            ),
            snapshotUrl to LinkPreviewHttpResponse(
                statusCode = 404,
                contentType = null,
                body = ByteArray(0)
            )
        )
        val httpClient = object : LinkPreviewHttpClient {
            override fun get(
                url: String,
                headers: Map<String, String>,
                maxBytes: Int?
            ): LinkPreviewHttpResponse {
                return responses[url]
                    ?: throw AssertionError("Unexpected network request to $url")
            }
        }

        val fetcher = LinkPreviewFetcher(context, httpClient) { filesDir }
        val result = fetcher.fetch("https://example.com")

        require(result is LinkPreviewResult.Success)
        val preview = result.preview
        assertEquals("Example Domain", preview.title)
        assertEquals("An example description", preview.description)
        assertEquals("https://example.com/cover.png", preview.imageUrl)
        val cached = preview.cachedImagePath
        assertNotNull(cached)
        assertTrue(File(cached!!).exists())
    }
}
