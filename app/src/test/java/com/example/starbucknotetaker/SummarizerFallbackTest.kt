package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SummarizerFallbackTest {

    @Test
    fun fallbackTraceIncludesReasonAndSentences() = runTest {
        val context = mock<Context>()
        val fetcher = mock<ModelFetcher>()
        runBlocking {
            whenever(fetcher.ensureModels(any())).thenReturn(ModelFetcher.Result.Failure("fail to fetch"))
        }
        val debugMessages = mutableListOf<String>()
        val summarizer = Summarizer(
            context = context,
            fetcher = fetcher,
            spFactory = { throw AssertionError("tokenizer should not be created in fallback") },
            nativeLoader = { throw AssertionError("native loader should not be invoked in fallback") },
            interpreterFactory = { throw AssertionError("interpreter should not be created in fallback") },
            logger = { _, _ -> },
            debugSink = { debugMessages.add(it) }
        )

        val source = "First sentence has coffee beans. Second sentence talks about roasting beans. Third sentence is ignored."
        val summary = summarizer.summarize(source)

        assertFalse(summary.isBlank())

        val trace = summarizer.consumeDebugTrace()
        assertTrue(
            "Expected fallback reason to appear in trace, got: $trace",
            trace.any { it.contains("fallback reason: models unavailable") }
        )
        assertTrue(
            "Expected fallback sentence log with actual text, got: $trace",
            trace.any { it.contains("fallback sentence[0]") && it.contains("coffee beans") }
        )
        assertTrue(
            "Expected fallback sentence log with actual text, got: $trace",
            trace.any { it.contains("fallback sentence[1]") && it.contains("roasting beans") }
        )
    }
}

