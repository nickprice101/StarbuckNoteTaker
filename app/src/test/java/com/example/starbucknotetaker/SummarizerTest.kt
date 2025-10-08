package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class SummarizerTest {
    private val context: Context = mock()
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("summarizer-test").toFile()
        whenever(context.filesDir).thenReturn(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun summarizeReturnsEnhancedReminderSummary() = runBlocking {
        val interpreter = StubInterpreter(floatArrayOf(0.1f, 0.9f))
        val mapping = mappingJson("FOOD_RECIPE", "REMINDER")
        val summarizer = createSummarizer(interpreter, mapping)

        val note = "Call dentist tomorrow: schedule six-month cleaning appointment, mention tooth sensitivity on lower left side."
        val summary = summarizer.summarize(note)

        assertEquals(
            "Reminder to schedule six-month cleaning appointment, mention tooth sensitivity on lower left side.",
            summary
        )
        assertEquals(Summarizer.SummarizerState.Ready, summarizer.state.value)
    }

    @Test
    fun summarizeFallsBackWhenInterpreterFails() = runBlocking {
        val interpreter = FailingInterpreter()
        val mapping = mappingJson("REMINDER")
        val summarizer = createSummarizer(interpreter, mapping)

        val note = """Line one about coffee.
Line two about pastries.""".trimIndent()
        val summary = summarizer.summarize(note)

        assertEquals("Line one about coffee. Line two about pastries.", summary)
        assertTrue(summarizer.state.value is Summarizer.SummarizerState.Fallback)
    }

    @Test
    fun fallbackSummaryUsesFirst150CharactersAcrossTwoLines() = runBlocking {
        val interpreter = FailingInterpreter()
        val mapping = mappingJson("REMINDER")
        val summarizer = createSummarizer(interpreter, mapping)

        val body = buildString {
            append("Weekly planning session covering roadmap adjustments, budget reallocations, vendor contract renewals, and team")
            append(" health initiatives requiring immediate attention from multiple department leads.")
        }
        val source = "Title: Sprint Sync\n\n$body"

        val summary = summarizer.summarize(source)
        val lines = summary.lines()
        val normalizedBody = body.trim().replace(Regex("\\s+"), " ")
        val combined = summary.replace("\n", "")

        assertFalse(summary.contains("Title:"))
        assertEquals(2, lines.size)
        assertEquals(normalizedBody.take(150), combined)
        assertTrue(summarizer.state.value is Summarizer.SummarizerState.Fallback)
    }

    @Test
    fun warmUpLoadsModel() = runBlocking {
        var created = false
        val interpreter = object : LiteInterpreter {
            override val inputTensorCount: Int = 1
            override fun getOutputTensor(index: Int): LiteTensor = StubTensor
            override fun getInputTensor(index: Int): LiteTensor = StubTensor
            override fun run(input: Any, output: Any) {}
            override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {}
            override fun close() {}
        }
        val mapping = mappingJson("SHOPPING_LIST")
        val summarizer = Summarizer(
            context,
            interpreterFactory = {
                created = true
                interpreter
            },
            logger = { _, _ -> },
            debugSink = { },
            assetLoader = assetLoader(mapping)
        )

        val state = summarizer.warmUp()

        assertTrue(created)
        assertEquals(Summarizer.SummarizerState.Ready, state)
    }

    @Test
    fun quickFallbackSummaryReturnsSmartPreview() {
        val note = "Morning jog report. Logged four miles around the lake with a friend, stopped for espresso after."
        val summarizer = createSummarizer(StubInterpreter(floatArrayOf(1f)), mappingJson("FOOD_RECIPE"))

        val quick = summarizer.quickFallbackSummary(note)
        val preview = Summarizer.lightweightPreview(note)

        assertEquals(Summarizer.smartTruncate(preview), quick)
    }

    private fun createSummarizer(interpreter: LiteInterpreter, mapping: String): Summarizer {
        return Summarizer(
            context,
            interpreterFactory = { interpreter },
            logger = { _, _ -> },
            debugSink = { },
            assetLoader = assetLoader(mapping)
        )
    }

    private fun assetLoader(mapping: String): (Context, String) -> ByteArrayInputStream {
        val assets = mapOf(
            Summarizer.MODEL_ASSET_NAME to ByteArray(4),
            Summarizer.CATEGORY_MAPPING_ASSET_NAME to mapping.toByteArray(Charsets.UTF_8)
        )
        return { _, name ->
            val bytes = assets[name] ?: throw IllegalArgumentException("missing asset: $name")
            ByteArrayInputStream(bytes)
        }
    }

    private fun mappingJson(vararg categories: String): String {
        val quoted = categories.joinToString(",") { "\"${it}\"" }
        return """{"categories":[${quoted}]}"""
    }

    private class StubInterpreter(private val scores: FloatArray) : LiteInterpreter {
        override val inputTensorCount: Int = 1
        override fun getOutputTensor(index: Int): LiteTensor = StubTensor
        override fun getInputTensor(index: Int): LiteTensor = StubTensor
        override fun run(input: Any, output: Any) {
            @Suppress("UNCHECKED_CAST")
            val out = output as Array<FloatArray>
            out[0] = scores.copyOf()
        }
        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {
            throw UnsupportedOperationException()
        }
        override fun close() {}
    }

    private class FailingInterpreter : LiteInterpreter {
        override val inputTensorCount: Int = 1
        override fun getOutputTensor(index: Int): LiteTensor = StubTensor
        override fun getInputTensor(index: Int): LiteTensor = StubTensor
        override fun run(input: Any, output: Any) {
            throw IllegalStateException("inference failed")
        }
        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {
            throw UnsupportedOperationException()
        }
        override fun close() {}
    }

    private object StubTensor : LiteTensor {
        override fun shape(): IntArray = intArrayOf()
        override fun shapeSignature(): IntArray = intArrayOf()
        override fun numElements(): Int = 0
    }
}
