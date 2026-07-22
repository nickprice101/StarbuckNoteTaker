package com.example.starbucknotetaker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssistantWebLookupTest {

    @Test
    fun shouldLookupDetectsCurrentOrExplicitWebQuestions() {
        assertTrue(AssistantWebLookup.shouldLookup("look up the latest Android release"))
        assertTrue(AssistantWebLookup.shouldLookup("what is the stock price today?"))
        assertTrue(AssistantWebLookup.shouldLookup("summarize https://example.com/news"))
        assertTrue(AssistantWebLookup.shouldLookup("Who wrote The Hobbit?"))
        assertFalse(AssistantWebLookup.shouldLookup("rewrite this note more clearly"))
        assertTrue(AssistantWebLookup.requiresInternet("what is the stock price today?"))
        assertFalse(AssistantWebLookup.requiresInternet("Who wrote The Hobbit?"))
        assertTrue(AssistantWebLookup.answerNeedsResearch("I don't know that answer."))
        assertFalse(AssistantWebLookup.answerNeedsResearch("The answer is available in this note."))
    }

    @Test
    fun researchOnlyAcceptsPublicHttpsUrls() {
        assertTrue(isPublicWebUrl("https://example.com/article"))
        assertFalse(isPublicWebUrl("http://example.com/article"))
        assertFalse(isPublicWebUrl("https://localhost/private"))
        assertFalse(isPublicWebUrl("https://127.0.0.1/private"))
        assertFalse(isPublicWebUrl("https://10.0.2.2/private"))
        assertFalse(isPublicWebUrl("file:///data/data/private"))
    }

    @Test
    fun lookupDiscoversDownloadsAndExtractsPageOnDevice() = runBlocking {
        val lookup = AssistantWebLookup(httpClient = FakeResearchHttpClient())

        val result = lookup.lookup("latest example")

        assertEquals("latest example", result.query)
        assertEquals(1, result.results.size)
        assertEquals("Example Article", result.results[0].title)
        assertEquals("https://example.com/one", result.results[0].url)
        assertTrue(result.results[0].snippet.contains("processed locally on the phone"))
        assertTrue(result.toPromptContext().contains("On-device web research"))
    }

    @Test
    fun lookupUsesRenderedPageFallbackForJavaScriptShell() = runBlocking {
        var rendered = false
        val lookup = AssistantWebLookup(
            httpClient = staticHttpClient("<html><body><div id='root'>Loading</div></body></html>"),
            renderedPageLoader = RenderedPageLoader { url ->
                rendered = true
                RenderedWebPage(
                    title = "Rendered article",
                    url = url,
                    text = "The dynamically rendered article contains enough useful factual content " +
                        "for the assistant to answer the user's research question entirely on the phone.",
                )
            },
            searchProviders = listOf(
                WebSearchProvider {
                    listOf(WebLookupEntry("Rendered article", "https://example.com/rendered", ""))
                },
            ),
        )

        val result = lookup.lookup("What does the rendered article say?")

        assertTrue(rendered)
        assertEquals(1, result.results.size)
        assertTrue(result.results.single().snippet.contains("dynamically rendered"))
    }

    @Test
    fun lookupCachesDiscoveryAndExtractedText() = runBlocking {
        var searches = 0
        var downloads = 0
        val cache = MemoryWebResearchCache()
        val lookup = AssistantWebLookup(
            httpClient = object : AssistantWebLookup.HttpClient {
                override suspend fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                ): WebLookupHttpResponse {
                    downloads++
                    return articleResponse("https://example.com/cached")
                }
            },
            researchCache = cache,
            searchProviders = listOf(
                WebSearchProvider {
                    searches++
                    listOf(WebLookupEntry("Cached Article", "https://example.com/cached", ""))
                },
            ),
        )

        assertTrue(lookup.lookup("cached facts").results.isNotEmpty())
        assertTrue(lookup.lookup("cached facts").results.isNotEmpty())

        assertEquals(1, searches)
        assertEquals(1, downloads)
    }

    @Test
    fun quickAnswerIncludesSourcesWithoutWaitingForModel() {
        val result = WebLookupResult(
            query = "latest example",
            results = listOf(
                WebLookupEntry(
                    title = "Example Result",
                    url = "https://example.com/one",
                    snippet = "A concise result snippet.",
                ),
            ),
        )

        val answer = AssistantWebLookup.quickAnswer("latest example", result)

        assertTrue(answer.contains("Example Result"))
        assertTrue(answer.contains("[Example Result](https://example.com/one)"))
        assertTrue(answer.contains("A concise result snippet."))
    }

    @Test
    fun offlineLookupReportsConnectivityWithoutIssuingRequests() = runBlocking {
        var requested = false
        val lookup = AssistantWebLookup(
            httpClient = object : AssistantWebLookup.HttpClient {
                override suspend fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                ): WebLookupHttpResponse {
                    requested = true
                    error("Network should not be called")
                }
            },
            internetAvailable = { false },
        )

        val result = lookup.lookup("latest Android version")

        assertEquals(WebLookupErrorKind.OFFLINE, result.errorKind)
        assertTrue(result.results.isEmpty())
        assertFalse(requested)
        assertTrue(AssistantWebLookup.INTERNET_REQUIRED_MESSAGE.contains("Connect this phone"))
    }

    private class FakeResearchHttpClient : AssistantWebLookup.HttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            maxBytes: Int,
        ): WebLookupHttpResponse = when {
            url.contains("duckduckgo.com") -> WebLookupHttpResponse(
                200,
                """
                    <html><body>
                      <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fone">Example Result</a>
                    </body></html>
                """.trimIndent().toByteArray(),
                finalUrl = url,
            )
            url.contains("wikipedia.org/w/api.php") -> WebLookupHttpResponse(
                200,
                "{\"query\":{\"search\":[]}}".toByteArray(),
                finalUrl = url,
                contentType = "application/json",
            )
            else -> articleResponse(url)
        }
    }

    companion object {
        private fun staticHttpClient(html: String): AssistantWebLookup.HttpClient =
            object : AssistantWebLookup.HttpClient {
                override suspend fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                ): WebLookupHttpResponse = WebLookupHttpResponse(
                    200,
                    html.toByteArray(),
                    finalUrl = url,
                )
            }

        private fun articleResponse(url: String): WebLookupHttpResponse = WebLookupHttpResponse(
            200,
            """
                <html><head><title>Example Article</title></head><body>
                  <nav>Navigation that must not enter research.</nav>
                  <article>
                    <h1>Example Article</h1>
                    <p>This example article is downloaded, parsed, ranked, and processed locally on the phone.</p>
                    <p>Its relevant text is supplied to the on-device assistant together with a standard source link.</p>
                  </article>
                </body></html>
            """.trimIndent().toByteArray(),
            finalUrl = url,
            contentType = "text/html; charset=utf-8",
        )
    }
}
