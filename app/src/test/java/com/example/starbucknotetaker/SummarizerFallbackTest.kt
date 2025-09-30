package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files

class SummarizerFallbackTest {

    @Test
    fun fallbackTraceIncludesReasonAndClassifierCategory() = runTest {
        val context = mock<Context>()
        val filesDir = Files.createTempDirectory("fallback_files").toFile()
        whenever(context.filesDir).thenReturn(filesDir)
        val debugMessages = mutableListOf<String>()
        val classifier = object : NoteNatureClassifier() {
            override suspend fun classify(text: String, event: NoteEvent?): NoteNatureLabel {
                return NoteNatureLabel(
                    NoteNatureType.GENERAL_NOTE,
                    NoteNatureType.GENERAL_NOTE.humanReadable,
                    confidence = 0.0
                )
            }
        }
        val summarizer = Summarizer(
            context = context,
            spFactory = { throw AssertionError("tokenizer should not be created in fallback") },
            nativeLoader = { throw AssertionError("native loader should not be invoked in fallback") },
            interpreterFactory = { throw AssertionError("interpreter should not be created in fallback") },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { debugMessages.add(it) },
            assetLoader = { _, _ -> throw IllegalStateException("asset missing") }
        )

        val source = """
            First sentence has coffee beans.
            Second sentence talks about roasting beans.
            Third sentence is ignored.
        """.trimIndent()
        val summary = summarizer.summarize(source)

        assertEquals(
            "First sentence has coffee beans. Second sentence talks about roasting beans.",
            summary
        )

        val trace = summarizer.consumeDebugTrace()
        assertTrue(
            "Expected fallback reason to appear in trace, got: $trace",
            trace.any { it.contains("fallback reason: models unavailable; classifier=GENERAL_NOTE") }
        )
        assertTrue(
            "Expected fallback classifier label to appear in trace, got: $trace",
            trace.any {
                it.contains("fallback classifier label: GENERAL_NOTE") &&
                        it.contains(NoteNatureType.GENERAL_NOTE.humanReadable)
            }
        )

        filesDir.deleteRecursively()
    }
}

