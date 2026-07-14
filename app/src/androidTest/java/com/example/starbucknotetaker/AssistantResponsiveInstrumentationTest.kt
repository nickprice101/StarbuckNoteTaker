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
        val lookup = AssistantWebLookup(
            object : AssistantWebLookup.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                ): WebLookupHttpResponse =
                    WebLookupHttpResponse(
                        statusCode = 200,
                        body = """
                            <html><body>
                              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fsource">Example Source</a>
                              <a class="result__snippet">A concise source snippet from the web.</a>
                            </body></html>
                        """.trimIndent().toByteArray(),
                    )
            },
        )

        val result = lookup.lookup("look up latest example")
        val answer = AssistantWebLookup.quickAnswer("look up latest example", result)

        assertTrue(result.results.isNotEmpty())
        assertTrue(answer.contains("Example Source"))
        assertTrue(answer.contains("https://example.com/source"))
    }
}
