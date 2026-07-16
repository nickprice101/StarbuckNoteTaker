package com.example.starbucknotetaker

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantResponsiveInstrumentationTest {
    @Test
    fun quickAnswer_handlesSimpleQuestionUnderBudget() {
        val startedAt = SystemClock.elapsedRealtime()

        val answer = QuickAssistantAnswerer.answer(
            question = "what is the capital of Iran?",
            noteContext = "General geography note",
        )

        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        assertNotNull(answer)
        requireNotNull(answer)
        assertTrue(answer.answer.contains("Tehran"))
        assertTrue("Quick answer took ${elapsedMs}ms", elapsedMs < 500L)
    }

    @Test
    fun professionalRewriteDraft_formatsNoteUnderBudget() {
        val startedAt = SystemClock.elapsedRealtime()

        val formatted = ProfessionalNoteFormatter.format(
            "client meeting notes: call alex tomorrow, update launch doc, send recap to team",
        )

        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        assertTrue(formatted.contains("Overview"))
        assertTrue(formatted.contains("Action Items"))
        assertTrue(formatted.contains("- Update launch doc."))
        assertTrue("Professional rewrite took ${elapsedMs}ms", elapsedMs < 500L)
    }

    @Test
    fun webLookup_parsesResultsOnDeviceWithoutWaitingForModel() = runBlocking {
        val startedAt = SystemClock.elapsedRealtime()
        val lookup = AssistantWebLookup(
            object : AssistantWebLookup.HttpClient {
                override suspend fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                ): WebLookupHttpResponse =
                    WebLookupHttpResponse(
                        statusCode = 200,
                        body = """
                            <html><body>
                              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fbaltic">Baltic Sea source</a>
                              <a class="result__snippet">The Baltic Sea is bordered by Denmark, Germany, Sweden, and other countries.</a>
                            </body></html>
                        """.trimIndent().toByteArray(),
                    )
            },
        )

        val question = "What countries border the Baltic Sea?"
        assertTrue(AssistantWebLookup.shouldLookup(question))
        val result = lookup.lookup(question)
        val answer = AssistantWebLookup.quickAnswer(question, result)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        assertTrue(result.results.isNotEmpty())
        assertTrue(answer.contains("Denmark"))
        assertTrue(answer.contains("https://example.com/baltic"))
        // SwiftShader and a concurrent background model preload introduce emulator
        // scheduling jitter; a sub-second local lookup remains an interactive response.
        assertTrue("Generic lookup took ${elapsedMs}ms", elapsedMs < 1_000L)
    }
}
