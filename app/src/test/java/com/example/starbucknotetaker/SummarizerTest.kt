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
    private val filesDir: File = Files.createTempDirectory("files").toFile()
    private val modelsDir: File = File(filesDir, Summarizer.MODELS_DIR_NAME).apply { mkdirs() }

    init {
        whenever(context.filesDir).thenReturn(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
        resetTokenizerLoader()
    }

    private fun writeModelFiles(
        encoderBytes: ByteArray = ByteArray(4),
        decoderBytes: ByteArray = ByteArray(4),
        tokenizerBytes: ByteArray = ByteArray(4),
    ) {
        File(modelsDir, Summarizer.ENCODER_ASSET_NAME).apply {
            parentFile?.mkdirs()
            writeBytes(encoderBytes)
        }
        File(modelsDir, Summarizer.DECODER_ASSET_NAME).apply { writeBytes(decoderBytes) }
        File(modelsDir, Summarizer.TOKENIZER_ASSET_NAME).apply { writeBytes(tokenizerBytes) }
    }

    @Test
    fun fallbackSummaryReturnsFirstTwoLines() = runBlocking {
        val summarizer = Summarizer(context, nativeLoader = { false }, debugSink = { })
        val text = """
            Security updates require MFA for all accounts.
            Lunch options were discussed briefly.
            The security updates also add mandatory VPN use.
        """.trimIndent()
        val summary = summarizer.fallbackSummary(text)
        assertEquals(
            "Security updates require MFA for all accounts. Lunch options were discussed briefly.",
            summary
        )
    }

    @Test
    fun summarizeFallsBackWhenTokenizerNativeMissing() = runBlocking {
        writeModelFiles(encoderBytes = ByteArray(1), decoderBytes = ByteArray(1), tokenizerBytes = ByteArray(1))

        val classifier = zeroConfidenceClassifier()
        val summarizer = Summarizer(
            context,
            nativeLoader = { false },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { }
        )

        val text = "One.\nTwo.\nThree."
        val result = summarizer.summarize(text)
        assertEquals("One. Two.", result)
    }

    @Test
    fun summarizeReturnsClassifierLabelWithinWordLimit() = runBlocking {
        val labelText = "Meeting recap covering agenda updates action items follow up planning deliverables and scheduling next steps across teams"
        val classifier = classifierWithLabel(
            NoteNatureLabel(
                NoteNatureType.MEETING_RECAP,
                labelText,
                confidence = 0.9
            )
        )

        val debugMessages = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val summarizer = Summarizer(
            context,
            classifierFactory = { classifier },
            nativeLoader = { throw AssertionError("native loader should not run during classification") },
            logger = { message, throwable -> logs.add("$message:${throwable.javaClass.simpleName}:${throwable.message}") },
            debugSink = { debugMessages.add(it) },
            assetLoader = { _, _ -> throw AssertionError("asset loader should not run during classification") }
        )

        val summary = summarizer.summarize("Any note content")
        val expected = Summarizer.trimToWordLimit(labelText, 15)
        val trace = summarizer.consumeDebugTrace()

        assertTrue("expected classifier trace but was: $trace logs=$logs", trace.any { it.contains("classifier summary output") })
        assertEquals(expected, summary)
        assertTrue(Summarizer.wordCount(summary) <= 15)

        summarizer.close()
    }

    @Test
    fun summarizeStopsDecodingWhenEosTokenAppears() = runBlocking {
        writeModelFiles()

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

        val classifier = zeroConfidenceClassifier()
        val summarizer = Summarizer(
            context,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { }
        )

        val summary = summarizer.summarize("Input text")

        assertEquals("21,22.", summary)
        assertEquals(decoder.expectedCalls, decoder.callCount)
        assertFalse("decoder should not run after EOS", decoder.extraInvocation)

        summarizer.close()
    }

    @Test
    fun summarizeAvoidsDegenerateOutput() = runBlocking {
        writeModelFiles()

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

        val classifier = zeroConfidenceClassifier()
        val summarizer = Summarizer(
            context,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { }
        )

        val input = "Sir Keir Starmer faces pressure over the Mandelson sacking."
        val summary = summarizer.summarize(input)

        assertEquals("Labour MPs pressure Starmer over Mandelson sacking", summary)
        assertEquals(Summarizer.SummarizerState.Ready, summarizer.state.value)

        summarizer.close()
    }

    @Test
    fun summarizeKeepsConciseHeadline() = runBlocking {
        writeModelFiles()

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

        val classifier = zeroConfidenceClassifier()
        val summarizer = Summarizer(
            context,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { }
        )

        val summary = summarizer.summarize("Rain forced the final match to be abandoned.")

        assertEquals("Rain abandons T20 decider", summary)
        summarizer.close()
    }

    @Test
    fun summarizeTurnsKeywordListIntoReadableSentence() = runBlocking {
        writeModelFiles()

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

        val classifier = zeroConfidenceClassifier()
        val summarizer = Summarizer(
            context,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { }
        )

        val input = "Project planning notes covering roadmap milestones"
        val summary = summarizer.summarize(input)
        val expected = listOf(400, 401, 402, 403, 404).joinToString(" ") { tokenMap[it] ?: "" }

        assertEquals(expected, summary)
        assertFalse(summary.startsWith("Summary highlights", ignoreCase = true))
        assertEquals(Summarizer.SummarizerState.Ready, summarizer.state.value)

        summarizer.close()
    }

    @Test
    fun summarizePrefersNoteKeywordsOverGenericOpeners() = runBlocking {
        writeModelFiles()

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

        val classifier = zeroConfidenceClassifier()
        val summarizer = Summarizer(
            context,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { }
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
    fun summarizeAcceptsMorphologicalParaphrases() = runBlocking {
        writeModelFiles()

        val tokenizer = mock<SentencePieceProcessor>()
        val encodedIds = intArrayOf(7, 8, 9, 10, 11, 12)
        whenever(tokenizer.encodeAsIds(any())).thenReturn(encodedIds)
        val tokenMap = mapOf(
            600 to "Team",
            601 to "organizing",
            602 to "project",
            603 to "scheduling",
            604 to "updated",
            605 to "release",
            610 to "organizes",
            611 to "projects",
            612 to "schedules",
            613 to "updates",
            614 to "releases"
        )
        whenever(tokenizer.decodeIds(any())).thenAnswer { invocation ->
            val ids = invocation.arguments[0] as IntArray
            ids.joinToString(" ") { tokenMap[it] ?: "[$it]" }.trim()
        }

        val attentionCapacity = max(encodedIds.size, tokenMap.size + 1)
        val decoder = PreferenceDecoderStub(
            listOf(
                listOf(600),
                listOf(601, 610),
                listOf(602, 611),
                listOf(603, 612),
                listOf(604, 613),
                listOf(605, 614)
            ),
            attentionCapacity
        )
        val interpreters = ArrayDeque<LiteInterpreter>().apply {
            add(EncoderStub(attentionCapacity))
            add(decoder)
        }

        val classifier = zeroConfidenceClassifier()
        val summarizer = Summarizer(
            context,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { }
        )

        val note = "Team organizes projects and schedules updates for releases."
        val summary = summarizer.summarize(note)

        assertEquals(
            "Team organizing project scheduling updated release",
            summary
        )
        assertEquals(Summarizer.SummarizerState.Ready, summarizer.state.value)

        summarizer.close()
    }

    @Test
    fun summarizeAcceptsSemanticParaphraseWithoutKeywordOverlap() = runBlocking {
        writeModelFiles()

        val tokenizer = mock<SentencePieceProcessor>()
        val prefix = "summarize the note type and structure: ${NoteNatureType.GENERAL_NOTE.humanReadable}\n\nNote: "
        val sourceTokens = intArrayOf(10, 11, 12, 13, 14)
        val summaryTokens = intArrayOf(20, 21, 22, 23)
        val abstractive = "Financial plan emphasizes launch readiness"

        whenever(tokenizer.encodeAsIds(any())).thenAnswer { invocation ->
            val textArg = invocation.arguments[0] as String
            when {
                textArg.startsWith(prefix) -> sourceTokens
                textArg == abstractive -> summaryTokens
                else -> intArrayOf()
            }
        }

        val tokenMap = mapOf(
            100 to "Financial",
            101 to "plan",
            102 to "emphasizes",
            103 to "launch",
            104 to "readiness"
        )
        whenever(tokenizer.decodeIds(any())).thenAnswer { invocation ->
            val ids = invocation.arguments[0] as IntArray
            ids.joinToString(" ") { tokenMap[it] ?: "" }.trim()
        }

        val generatedTokens = intArrayOf(100, 101, 102, 103, 104)
        val attentionCapacity = max(sourceTokens.size, generatedTokens.size + 1)
        val encoder = SemanticEncoderStub(
            mapOf(
                sourceTokens.first() to floatArrayOf(0.6f, 0.8f, 0f, 0f),
                summaryTokens.first() to floatArrayOf(0.6f, 0.8f, 0f, 0f)
            ),
            attentionCapacity
        )
        val decoder = DecoderStub(generatedTokens, attentionCapacity)
        val interpreters = ArrayDeque<LiteInterpreter>().apply {
            add(encoder)
            add(decoder)
        }

        val classifier = zeroConfidenceClassifier()
        val summarizer = Summarizer(
            context,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { }
        )

        val source = "Roadmap review covers milestones, budgets, timeline risks, and rollout preparation."
        val summary = summarizer.summarize(source)

        assertEquals(abstractive, summary)
        assertEquals(Summarizer.SummarizerState.Ready, summarizer.state.value)

        summarizer.close()
    }

    @Test
    fun summarizeKeepsHyphenatedParaphrase() = runBlocking {
        writeModelFiles()

        val tokenizer = mock<SentencePieceProcessor>()
        val encodedIds = intArrayOf(7, 8, 9, 10, 11, 12)
        whenever(tokenizer.encodeAsIds(any())).thenReturn(encodedIds)
        val tokenMap = mapOf(
            700 to "Follow",
            701 to "up",
            702 to "handles",
            703 to "handoffs",
            704 to "signoffs"
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
                listOf(700),
                listOf(701),
                listOf(702),
                listOf(703),
                listOf(704)
            ),
            attentionCapacity
        )
        val interpreters = ArrayDeque<LiteInterpreter>().apply {
            add(EncoderStub(attentionCapacity))
            add(decoder)
        }

        val classifier = zeroConfidenceClassifier()
        val summarizer = Summarizer(
            context,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { }
        )

        val note = "Follow-up addresses hand-offs and sign-offs."
        val summary = summarizer.summarize(note)
        val expected = listOf(700, 701, 702, 703, 704)
            .joinToString(" ") { tokenMap[it] ?: "" }

        assertEquals(expected, summary)
        assertEquals(Summarizer.SummarizerState.Ready, summarizer.state.value)

        summarizer.close()
    }

    @Test
    fun summarizeFeedsFullHistoryWhenDecoderLacksCache() = runBlocking {
        writeModelFiles()

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

        val classifier = zeroConfidenceClassifier()
        val summarizer = Summarizer(
            context,
            spFactory = { tokenizer },
            nativeLoader = { true },
            interpreterFactory = { interpreters.removeFirst() },
            classifierFactory = { classifier },
            logger = { _, _ -> },
            debugSink = { }
        )

        val summary = summarizer.summarize("Input text")

        assertEquals("21,22.", summary)
        assertEquals(decoder.expectedCalls, decoder.callCount)

        summarizer.close()
    }

    @Test
    fun warmUpReportsFallbackWhenNativeTokenizerMissing() = runBlocking {
        writeModelFiles(encoderBytes = ByteArray(1), decoderBytes = ByteArray(1), tokenizerBytes = ByteArray(1))

        val summarizer = Summarizer(context, nativeLoader = { false }, logger = { _, _ -> }, debugSink = { })

        val state = summarizer.warmUp()
        assertEquals(Summarizer.SummarizerState.Fallback, state)
    }

    @Test
    fun warmUpIsReadyWhenNativeTokenizerLoads() = runBlocking {
        writeModelFiles()

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
            spFactory = { _ -> tokenizer },
            nativeLoader = { NativeLibraryLoader.ensureTokenizer(it) },
            interpreterFactory = { interpreters.removeFirst() },
            logger = { _, _ -> },
            debugSink = { }
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

    private fun zeroConfidenceClassifier(): NoteNatureClassifier {
        return classifierWithLabel(
            NoteNatureLabel(
                NoteNatureType.GENERAL_NOTE,
                NoteNatureType.GENERAL_NOTE.humanReadable,
                confidence = 0.0
            )
        )
    }

    private fun classifierWithLabel(label: NoteNatureLabel): NoteNatureClassifier {
        return object : NoteNatureClassifier() {
            override suspend fun classify(text: String, event: NoteEvent?): NoteNatureLabel {
                return label
            }
        }
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

    private class SemanticEncoderStub(
        private val embeddings: Map<Int, FloatArray>,
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

        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {
            val attention = (inputs[0] as Array<IntArray>)[0]
            val tokens = (inputs[1] as Array<IntArray>)[0]
            val hidden = outputs[0] as Array<Array<FloatArray>>
            if (hidden.isEmpty() || hidden[0].isEmpty()) return
            if (hidden[0][0].isEmpty()) return

            val length = attention.count { it == 1 }.coerceAtMost(hidden[0].size)
            if (tokens.isEmpty() || length <= 0) return

            val vector = embeddings[tokens[0]] ?: FloatArray(hiddenSize)
            val dim = kotlin.math.min(vector.size, hidden[0][0].size)
            for (i in 0 until length) {
                val row = hidden[0][i]
                for (d in 0 until dim) {
                    row[d] = vector[d]
                }
            }
        }

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
