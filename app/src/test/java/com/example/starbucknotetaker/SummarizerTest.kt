package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class SummarizerTest {
    private val context: Context = mock()
    private val modelsDir: File = Files.createTempDirectory("models").toFile()

    init {
        whenever(context.filesDir).thenReturn(modelsDir)
    }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
        resetPenguinLoader()
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
        val spFile = File(modelsDir, ModelFetcher.SPIECE_NAME).apply { writeBytes(byteArrayOf()) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, spFile)
        )

        val summarizer = Summarizer(context, fetcher, nativeLoader = { false }, logger = { _, _ -> }, debug = { })

        val text = "One. Two. Three."
        val result = summarizer.summarize(text)
        assertEquals("One. Two", result)
    }

    @Test
    fun warmUpReportsFallbackWhenNativeTokenizerMissing() = runBlocking {
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(byteArrayOf()) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(byteArrayOf()) }
        val spFile = File(modelsDir, ModelFetcher.SPIECE_NAME).apply { writeBytes(byteArrayOf()) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, spFile)
        )

        val summarizer = Summarizer(context, fetcher, nativeLoader = { false }, logger = { _, _ -> }, debug = { })

        val state = summarizer.warmUp()
        assertEquals(Summarizer.SummarizerState.Fallback, state)
    }

    @Test
    fun warmUpIsReadyWhenNativeTokenizerLoads() = runBlocking {
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(ByteArray(4)) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(ByteArray(4)) }
        val spFile = File(modelsDir, ModelFetcher.SPIECE_NAME).apply { writeBytes(ByteArray(4)) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(
            ModelFetcher.Result.Success(encFile, decFile, spFile)
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
            spFactory = { tokenizer },
            nativeLoader = { NativeLibraryLoader.ensurePenguin(it) },
            interpreterFactory = { interpreters.removeFirst() },
            logger = { _, _ -> },
            debug = { }
        )

        val state = summarizer.warmUp()
        assertEquals(Summarizer.SummarizerState.Ready, state)
        summarizer.close()

        assertTrue(isPenguinLoaded())
    }

    private fun resetPenguinLoader() {
        val field = NativeLibraryLoader::class.java.getDeclaredField("penguinLoaded")
        field.isAccessible = true
        val flag = field.get(NativeLibraryLoader) as AtomicBoolean
        flag.set(false)
        NativeLibraryLoader.setLoadLibraryOverrideForTesting(null)
    }

    private fun isPenguinLoaded(): Boolean {
        val field = NativeLibraryLoader::class.java.getDeclaredField("penguinLoaded")
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

        override fun run(inputs: Array<Any>, outputs: Array<Any>) {}

        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {}

        override fun close() {}
    }

    private class FakeTensor(
        private val shapeValues: IntArray = intArrayOf(1),
        private val elements: Int = 1
    ) : LiteTensor {
        override fun shape(): IntArray = shapeValues
        override fun numElements(): Int = elements
    }
}

