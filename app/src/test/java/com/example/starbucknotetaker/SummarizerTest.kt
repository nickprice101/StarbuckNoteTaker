package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class SummarizerTest {
    private val context: Context = mock()
    private val modelsDir: File = Files.createTempDirectory("models").toFile()

    init {
        whenever(context.filesDir).thenReturn(modelsDir)
    }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
        resetTokenizerLoader()
    }

    @Test
    fun fallbackSummaryReturnsFirstTwoSentences() {
        val summarizer = Summarizer(context, nativeLoader = { false })
        val text = "Sentence one. Sentence two. Sentence three."
        val summary = summarizer.fallbackSummary(text)
        assertEquals("Sentence one. Sentence two", summary)
    }

    @Test
    fun summarizeFallsBackWhenTokenizerNativeMissing() = runBlocking {
        // Create dummy model files so fetcher doesn't attempt network
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(byteArrayOf()) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(byteArrayOf()) }
        val tokenizerFile = File(modelsDir, ModelFetcher.TOKENIZER_NAME).apply { writeBytes(byteArrayOf()) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, tokenizerFile)
        )

        val summarizer = Summarizer(context, fetcher, nativeLoader = { false }, logger = { _, _ -> }, debug = { })

        val text = "One. Two. Three."
        val result = summarizer.summarize(text)
        assertEquals("One. Two", result)
    }

    @Test
    fun summarizeStopsDecodingWhenEosTokenAppears() = runBlocking {
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(ByteArray(4)) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(ByteArray(4)) }
        val tokenizerFile = File(modelsDir, ModelFetcher.TOKENIZER_NAME).apply { writeBytes(ByteArray(4)) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, tokenizerFile)
        )

        val tokenizer = mock<SentencePieceProcessor>()
        val encodedIds = intArrayOf(7, 8, 9)
        whenever(tokenizer.encodeAsIds(any())).thenReturn(encodedIds)
        whenever(tokenizer.decodeIds(any())).thenAnswer { invocation ->
            val ids = invocation.arguments[0] as IntArray
            ids.joinToString(",")
        }

        val generatedTokens = intArrayOf(21, 22)
        val attentionCapacity = max(encodedIds.size, generatedTokens.size + 1)
        val decoder = DecoderStub(generatedTokens, attentionCapacity)
        val interpreters = ArrayDeque<LiteInterpreter>().apply {
            add(EncoderStub(attentionCapacity))
            add(decoder)
        }

        val summarizer = Summarizer(
            context,
            fetcher = fetcher,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            logger = { _, _ -> },
            debug = { }
        )

        val summary = summarizer.summarize("Input text")

        assertEquals("Summary focuses on 21,22.", summary)
        assertEquals(decoder.expectedCalls, decoder.callCount)
        assertFalse("decoder should not run after EOS", decoder.extraInvocation)

        summarizer.close()
    }

    @Test
    fun summarizeAvoidsDegenerateOutput() = runBlocking {
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(ByteArray(4)) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(ByteArray(4)) }
        val tokenizerFile = File(modelsDir, ModelFetcher.TOKENIZER_NAME).apply { writeBytes(ByteArray(4)) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, tokenizerFile)
        )

        val tokenizer = mock<SentencePieceProcessor>()
        val encodedIds = intArrayOf(7, 8, 9, 10, 11, 12)
        whenever(tokenizer.encodeAsIds(any())).thenReturn(encodedIds)
        val tokenMap = mapOf(
            200 to "Labour",
            201 to "MPs",
            202 to "pressure",
            203 to "Starmer",
            204 to "over",
            205 to "Mandelson",
            206 to "sacking"
        )
        whenever(tokenizer.decodeIds(any())).thenAnswer { invocation ->
            val ids = invocation.arguments[0] as IntArray
            ids.joinToString(" ") { tokenMap[it] ?: "[$it]" }.trim()
        }

        val attentionCapacity = max(encodedIds.size, tokenMap.size + 1)
        val decoder = PreferenceDecoderStub(
            listOf(
                listOf(200, 201),
                listOf(200, 201),
                listOf(201, 202),
                listOf(203),
                listOf(204),
                listOf(205),
                listOf(206)
            ),
            attentionCapacity
        )
        val interpreters = ArrayDeque<LiteInterpreter>().apply {
            add(EncoderStub(attentionCapacity))
            add(decoder)
        }

        val summarizer = Summarizer(
            context,
            fetcher = fetcher,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            logger = { _, _ -> },
            debug = { }
        )

        val input = "Sir Keir Starmer faces pressure over the Mandelson sacking."
        val summary = summarizer.summarize(input)

        assertEquals("Labour MPs pressure Starmer over Mandelson sacking", summary)
        assertEquals(Summarizer.SummarizerState.Ready, summarizer.state.value)

        summarizer.close()
    }

    @Test
    fun summarizeKeepsConciseHeadline() = runBlocking {
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(ByteArray(4)) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(ByteArray(4)) }
        val tokenizerFile = File(modelsDir, ModelFetcher.TOKENIZER_NAME).apply { writeBytes(ByteArray(4)) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, tokenizerFile)
        )

        val tokenizer = mock<SentencePieceProcessor>()
        val encodedIds = intArrayOf(7, 8, 9, 10)
        whenever(tokenizer.encodeAsIds(any())).thenReturn(encodedIds)
        val tokenMap = mapOf(
            500 to "Rain",
            501 to "abandons",
            502 to "T20",
            503 to "decider"
        )
        whenever(tokenizer.decodeIds(any())).thenAnswer { invocation ->
            val ids = invocation.arguments[0] as IntArray
            ids.filter { tokenMap.containsKey(it) }
                .joinToString(" ") { tokenMap[it] ?: "" }
                .trim()
        }

        val generatedTokens = intArrayOf(500, 501, 502, 503)
        val attentionCapacity = max(encodedIds.size, generatedTokens.size + 1)
        val decoder = DecoderStub(generatedTokens, attentionCapacity)
        val interpreters = ArrayDeque<LiteInterpreter>().apply {
            add(EncoderStub(attentionCapacity))
            add(decoder)
        }

        val summarizer = Summarizer(
            context,
            fetcher = fetcher,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            logger = { _, _ -> },
            debug = { }
        )

        val summary = summarizer.summarize("Rain forced the final match to be abandoned.")

        assertEquals("Rain abandons T20 decider", summary)
        summarizer.close()
    }

    @Test
    fun summarizeTurnsKeywordListIntoReadableSentence() = runBlocking {
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(ByteArray(4)) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(ByteArray(4)) }
        val tokenizerFile = File(modelsDir, ModelFetcher.TOKENIZER_NAME).apply { writeBytes(ByteArray(4)) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, tokenizerFile)
        )

        val tokenizer = mock<SentencePieceProcessor>()
        val encodedIds = intArrayOf(7, 8, 9, 10)
        whenever(tokenizer.encodeAsIds(any())).thenReturn(encodedIds)
        val tokenMap = mapOf(
            400 to "Timeline",
            401 to "milestones",
            402 to "roadmap",
            403 to "deliverables",
            404 to "updates",
            405 to "timeline"
        )
        whenever(tokenizer.decodeIds(any())).thenAnswer { invocation ->
            val ids = invocation.arguments[0] as IntArray
            ids.filter { tokenMap.containsKey(it) }
                .joinToString(" ") { tokenMap[it] ?: "" }
                .trim()
        }

        val attentionCapacity = max(encodedIds.size, tokenMap.size + 1)
        val decoder = PreferenceDecoderStub(
            listOf(
                listOf(400, 401),
                listOf(401, 402),
                listOf(402, 403),
                listOf(403, 404),
                listOf(404, 405)
            ),
            attentionCapacity
        )
        val interpreters = ArrayDeque<LiteInterpreter>().apply {
            add(EncoderStub(attentionCapacity))
            add(decoder)
        }

        val summarizer = Summarizer(
            context,
            fetcher = fetcher,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            logger = { _, _ -> },
            debug = { }
        )

        val input = "Project planning notes covering roadmap milestones"
        val summary = summarizer.summarize(input)

        assertEquals(
            "Summary highlights timeline, milestones, roadmap, deliverables, and updates.",
            summary
        )
        assertEquals(Summarizer.SummarizerState.Ready, summarizer.state.value)

        summarizer.close()
    }

    @Test
    fun summarizePrefersNoteKeywordsOverGenericOpeners() = runBlocking {
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(ByteArray(4)) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(ByteArray(4)) }
        val tokenizerFile = File(modelsDir, ModelFetcher.TOKENIZER_NAME).apply { writeBytes(ByteArray(4)) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, tokenizerFile)
        )

        val tokenizer = mock<SentencePieceProcessor>()
        val encodedIds = intArrayOf(7, 8, 9, 10, 11, 12)
        whenever(tokenizer.encodeAsIds(any())).thenReturn(encodedIds)
        val tokenMap = mapOf(
            300 to "Summary",
            301 to "Report",
            302 to "Tweet",
            303 to "project",
            304 to "meeting",
            305 to "notes",
            306 to "timeline",
            307 to "updates",
            308 to "budget",
            309 to "discussion"
        )
        whenever(tokenizer.decodeIds(any())).thenAnswer { invocation ->
            val ids = invocation.arguments[0] as IntArray
            ids.joinToString(" ") { tokenMap[it] ?: "[$it]" }.trim()
        }

        val attentionCapacity = max(encodedIds.size, tokenMap.size + 1)
        val decoder = PreferenceDecoderStub(
            listOf(
                listOf(300, 303, 304),
                listOf(301, 304, 305),
                listOf(302, 304, 306),
                listOf(306, 307, 308),
                listOf(308, 309),
                listOf(309)
            ),
            attentionCapacity
        )
        val interpreters = ArrayDeque<LiteInterpreter>().apply {
            add(EncoderStub(attentionCapacity))
            add(decoder)
        }

        val summarizer = Summarizer(
            context,
            fetcher = fetcher,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            logger = { _, _ -> },
            debug = { }
        )

        val note = "Project meeting notes include timeline updates and budget discussion."
        val summary = summarizer.summarize(note)
        val lowerSummary = summary.lowercase()

        assertFalse("summary should not echo generic tweet opener", lowerSummary.contains("tweet"))
        assertTrue("summary should include meeting keyword", lowerSummary.contains("meeting"))
        assertTrue("summary should include budget keyword", lowerSummary.contains("budget"))
        assertEquals(Summarizer.SummarizerState.Ready, summarizer.state.value)

        summarizer.close()
    }

    @Test
    fun summarizeFeedsFullHistoryWhenDecoderLacksCache() = runBlocking {
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(ByteArray(4)) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(ByteArray(4)) }
        val tokenizerFile = File(modelsDir, ModelFetcher.TOKENIZER_NAME).apply { writeBytes(ByteArray(4)) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, tokenizerFile)
        )

        val tokenizer = mock<SentencePieceProcessor>()
        val encodedIds = intArrayOf(7, 8, 9)
        whenever(tokenizer.encodeAsIds(any())).thenReturn(encodedIds)
        whenever(tokenizer.decodeIds(any())).thenAnswer { invocation ->
            val ids = invocation.arguments[0] as IntArray
            ids.joinToString(",")
        }

        val generatedTokens = intArrayOf(21, 22)
        val attentionCapacity = max(encodedIds.size, generatedTokens.size + 1)
        val decoder = DecoderWithoutCacheStub(generatedTokens, attentionCapacity)
        val interpreters = ArrayDeque<LiteInterpreter>().apply {
            add(EncoderStub(attentionCapacity))
            add(decoder)
        }

        val summarizer = Summarizer(
            context,
            fetcher = fetcher,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            logger = { _, _ -> },
            debug = { }
        )

        val summary = summarizer.summarize("Input text")

        assertEquals("Summary focuses on 21,22.", summary)
        assertEquals(decoder.expectedCalls, decoder.callCount)

        summarizer.close()
    }

    @Test
    fun warmUpReportsFallbackWhenNativeTokenizerMissing() = runBlocking {
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(byteArrayOf()) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(byteArrayOf()) }
        val tokenizerFile = File(modelsDir, ModelFetcher.TOKENIZER_NAME).apply { writeBytes(byteArrayOf()) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, tokenizerFile)
        )

        val summarizer = Summarizer(context, fetcher, nativeLoader = { false }, logger = { _, _ -> }, debug = { })

        val state = summarizer.warmUp()
        assertEquals(Summarizer.SummarizerState.Fallback, state)
    }

    @Test
    fun warmUpIsReadyWhenNativeTokenizerLoads() = runBlocking {
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(ByteArray(4)) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(ByteArray(4)) }
        val tokenizerFile = File(modelsDir, ModelFetcher.TOKENIZER_NAME).apply { writeBytes(ByteArray(4)) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, tokenizerFile)
        )

        val tokenizer = mock<SentencePieceProcessor>()

        NativeLibraryLoader.setLoadLibraryOverrideForTesting { _ -> }

        val interpreters = ArrayDeque<LiteInterpreter>().apply {
            add(
                FakeInterpreter(
                    inputTensorCount = 2,
                    outputTensors = mapOf(0 to FakeTensor(intArrayOf(1, 1, 1))),
                    inputTensors = emptyMap()
                )
            )
            add(
                FakeInterpreter(
                    inputTensorCount = 4,
                    outputTensors = mapOf(1 to FakeTensor(intArrayOf(1), 1)),
                    inputTensors = mapOf(3 to FakeTensor(intArrayOf(1), 1))
                )
            )
        }

        val summarizer = Summarizer(
            context,
            fetcher = fetcher,
            spFactory = { _ -> tokenizer },
            nativeLoader = { NativeLibraryLoader.ensureTokenizer(it) },
            interpreterFactory = { interpreters.removeFirst() },
            logger = { _, _ -> },
            debug = { }
        )

        val state = summarizer.warmUp()
        assertEquals(Summarizer.SummarizerState.Ready, state)
        summarizer.close()

        assertTrue(isTokenizerLoaded())
    }

    private fun resetTokenizerLoader() {
        val field = NativeLibraryLoader::class.java.getDeclaredField("tokenizerLoaded")
        field.isAccessible = true
        val flag = field.get(NativeLibraryLoader) as AtomicBoolean
        flag.set(false)
        NativeLibraryLoader.setLoadLibraryOverrideForTesting(null)
    }

    private fun isTokenizerLoaded(): Boolean {
        val field = NativeLibraryLoader::class.java.getDeclaredField("tokenizerLoaded")
        field.isAccessible = true
        val flag = field.get(NativeLibraryLoader) as AtomicBoolean
        return flag.get()
    }

    private class FakeInterpreter(
        override val inputTensorCount: Int,
        private val outputTensors: Map<Int, LiteTensor>,
        private val inputTensors: Map<Int, LiteTensor>
    ) : LiteInterpreter {
        override fun getOutputTensor(index: Int): LiteTensor = outputTensors[index] ?: FakeTensor()

        override fun getInputTensor(index: Int): LiteTensor = inputTensors[index] ?: FakeTensor()

        override fun run(input: Any, output: Any) {}

        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {}

        override fun close() {}
    }

    private class EncoderStub(
        private val tokenCapacity: Int,
        private val hiddenSize: Int = 4
    ) : LiteInterpreter {
        override val inputTensorCount: Int = 2

        override fun getOutputTensor(index: Int): LiteTensor =
            FakeTensor(intArrayOf(1, tokenCapacity, hiddenSize), tokenCapacity * hiddenSize)

        override fun getInputTensor(index: Int): LiteTensor = when (index) {
            0, 1 -> FakeTensor(intArrayOf(1, tokenCapacity), tokenCapacity)
            else -> FakeTensor()
        }

        override fun run(input: Any, output: Any) {}

        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {}

        override fun close() {}
    }

    private class DecoderStub(
        private val tokens: IntArray,
        private val attentionCapacity: Int,
        private val hiddenSize: Int = 4
    ) : LiteInterpreter {
        override val inputTensorCount: Int = 3

        val expectedCalls: Int = tokens.size + 1
        var callCount: Int = 0
            private set
        var extraInvocation: Boolean = false
            private set

        override fun getOutputTensor(index: Int): LiteTensor = FakeTensor(intArrayOf(1, 1, 1), 1)

        override fun getInputTensor(index: Int): LiteTensor = when (index) {
            0 -> FakeTensor(intArrayOf(1, attentionCapacity), attentionCapacity)
            1 -> FakeTensor(intArrayOf(1, 1), 1)
            2 -> FakeTensor(intArrayOf(1, attentionCapacity, hiddenSize), attentionCapacity * hiddenSize)
            else -> FakeTensor()
        }

        override fun run(input: Any, output: Any) {}

        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {
            val logits = outputs[0] as Array<Array<FloatArray>>
            val scores = logits[0][0]
            scores.fill(0f)
            val nextToken = when {
                callCount < tokens.size -> tokens[callCount]
                callCount == tokens.size -> EOS_ID
                else -> {
                    extraInvocation = true
                    tokens.last()
                }
            }
            scores[nextToken] = 1f
            callCount++
        }

        override fun close() {}

        private companion object {
            private const val EOS_ID = 1
        }
    }

    private class PreferenceDecoderStub(
        private val preferences: List<List<Int>>,
        private val attentionCapacity: Int,
        private val hiddenSize: Int = 4
    ) : LiteInterpreter {
        override val inputTensorCount: Int = 3

        private var callCount: Int = 0

        override fun getOutputTensor(index: Int): LiteTensor = FakeTensor(intArrayOf(1, 1, 1), 1)

        override fun getInputTensor(index: Int): LiteTensor = when (index) {
            0 -> FakeTensor(intArrayOf(1, attentionCapacity), attentionCapacity)
            1 -> FakeTensor(intArrayOf(1, 1), 1)
            2 -> FakeTensor(intArrayOf(1, attentionCapacity, hiddenSize), attentionCapacity * hiddenSize)
            else -> FakeTensor()
        }

        override fun run(input: Any, output: Any) {}

        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {
            val logits = outputs[0] as Array<Array<FloatArray>>
            val scores = logits[0][0]
            scores.fill(-100f)

            if (callCount < preferences.size) {
                val options = preferences[callCount]
                var weight = options.size.toFloat()
                for (candidate in options) {
                    scores[candidate] = weight
                    weight -= 1f
                }
                scores[EOS_ID] = -100f
            } else {
                scores[EOS_ID] = 100f
            }

            callCount++
        }

        override fun close() {}

        private companion object {
            private const val EOS_ID = 1
        }
    }

    private class DecoderWithoutCacheStub(
        private val tokens: IntArray,
        private val attentionCapacity: Int,
        private val hiddenSize: Int = 4
    ) : LiteInterpreter {
        override val inputTensorCount: Int = 3

        val expectedCalls: Int = tokens.size + 1
        var callCount: Int = 0
            private set

        override fun getOutputTensor(index: Int): LiteTensor = FakeTensor(intArrayOf(1, 1, 1), 1)

        override fun getInputTensor(index: Int): LiteTensor = when (index) {
            0 -> FakeTensor(intArrayOf(1, attentionCapacity), attentionCapacity)
            1 -> FakeTensor(intArrayOf(1, attentionCapacity), attentionCapacity)
            2 -> FakeTensor(intArrayOf(1, attentionCapacity, hiddenSize), attentionCapacity * hiddenSize)
            else -> FakeTensor()
        }

        override fun run(input: Any, output: Any) {}

        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {
            val attentionInput = inputs[0] as Array<IntArray>
            val tokenInput = inputs[1] as Array<IntArray>

            val expectedLength = (callCount + 1).coerceAtMost(attentionCapacity)
            check(tokenInput[0][0] == START_TOKEN) {
                "decoder should receive start token at position 0"
            }
            for (i in 1 until expectedLength) {
                val expected = tokens[i - 1]
                check(tokenInput[0][i] == expected) {
                    "decoder token mismatch at position $i on call $callCount"
                }
            }
            for (i in expectedLength until attentionCapacity) {
                check(tokenInput[0][i] == 0) {
                    "decoder token input should be zero-padded after position $expectedLength"
                }
            }

            for (i in 0 until expectedLength) {
                check(attentionInput[0][i] == 1) {
                    "decoder attention mask missing at position $i"
                }
            }
            for (i in expectedLength until attentionCapacity) {
                check(attentionInput[0][i] == 0) {
                    "decoder attention mask should be zero after position $expectedLength"
                }
            }

            val logits = outputs[0] as Array<Array<FloatArray>>
            val scores = logits[0][0]
            scores.fill(0f)
            val nextToken = if (callCount < tokens.size) tokens[callCount] else EOS_ID
            scores[nextToken] = 1f
            callCount++
        }

        override fun close() {}

        private companion object {
            private const val START_TOKEN = 0
            private const val EOS_ID = 1
        }
    }

    private class FakeTensor(
        private val shapeValues: IntArray = intArrayOf(1),
        private val elements: Int = 1,
        private val signatureValues: IntArray = shapeValues
    ) : LiteTensor {
        override fun shape(): IntArray = shapeValues
        override fun shapeSignature(): IntArray = signatureValues
        override fun numElements(): Int = elements
    }
}
