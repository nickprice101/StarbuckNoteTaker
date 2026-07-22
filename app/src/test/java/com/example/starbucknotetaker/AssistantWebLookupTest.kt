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
    fun lookupParsesDuckDuckGoHtmlResults() = runBlocking {
        val html = """
            <html><body>
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fone">Example Result</a>
              <a class="result__snippet">A concise result snippet for the assistant.</a>
            </body></html>
        """.trimIndent()
        val lookup = AssistantWebLookup(
            object : AssistantWebLookup.HttpClient {
                override suspend fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                ): WebLookupHttpResponse =
                    WebLookupHttpResponse(200, html.toByteArray())

                override suspend fun post(
                    url: String,
                    body: ByteArray,
                    headers: Map<String, String>,
                    maxBytes: Int,
                ): WebLookupHttpResponse = WebLookupHttpResponse(
                    200,
                    """
                    {
                      "success": true,
                      "results": [{
                        "url": "https://example.com/one",
                        "success": true,
                        "markdown": {"fit_markdown": "Example facts extracted by Crawl4AI for the concise result snippet."}
                      }]
                    }
                    """.trimIndent().toByteArray(),
                )
            },
            crawl4AiConfig = Crawl4AiConfig("https://crawl4ai.test", "test-token"),
        )

        val result = lookup.lookup("latest example")

        assertEquals("latest example", result.query)
        assertEquals(1, result.results.size)
        assertEquals("Example Result", result.results[0].title)
        assertEquals("https://example.com/one", result.results[0].url)
        assertTrue(result.results[0].snippet.contains("extracted by Crawl4AI"))
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
                )
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
            crawl4AiConfig = Crawl4AiConfig("https://crawl4ai.test"),
            internetAvailable = { false },
        )

        val result = lookup.lookup("latest Android version")

        assertEquals(WebLookupErrorKind.OFFLINE, result.errorKind)
        assertTrue(result.results.isEmpty())
        assertFalse(requested)
    }
}
