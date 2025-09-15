package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import com.example.starbucknotetaker.SentencePieceProcessor
import java.io.File
import java.nio.file.Files

class SummarizerTest {
    private val context: Context = mock()
    private val modelsDir: File = Files.createTempDirectory("models").toFile()

    init {
        whenever(context.filesDir).thenReturn(modelsDir)
    }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
    }

    @Test
    fun fallbackSummaryReturnsFirstTwoSentences() {
        val summarizer = Summarizer(context)
        val text = "Sentence one. Sentence two. Sentence three."
        val summary = summarizer.fallbackSummary(text)
        assertEquals("Sentence one. Sentence two", summary)
    }

    @Test
    fun summarizeDownloadsAndUsesLatestModels() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody("enc"))
        server.enqueue(MockResponse().setBody("dec"))
        server.enqueue(MockResponse().setBody("sp"))

        val client = OkHttpClient()
        val fetcher = ModelFetcher(server.url("/").toString(), client)

        fetcher.ensureModels(context)

        val savedDir = File(modelsDir, "models")
        assertTrue(File(savedDir, ModelFetcher.ENCODER_NAME).exists())
        assertTrue(File(savedDir, ModelFetcher.DECODER_NAME).exists())
        assertTrue(File(savedDir, ModelFetcher.SPIECE_NAME).exists())

        val summarizer = Summarizer(context, fetcher)

        val encoder = mock<Interpreter>()
        val decoder = mock<Interpreter>()
        val tokenizer = mock<SentencePieceProcessor>()
        val tensor = mock<Tensor>()

        whenever(tokenizer.encodeAsIds(any())).thenReturn(intArrayOf(5, 6, 7))
        whenever(tokenizer.decodeIds(any())).thenReturn("mock summary")

        whenever(encoder.getOutputTensor(0)).thenReturn(tensor)
        whenever(tensor.shape()).thenReturn(intArrayOf(1, 1, 1))
        doAnswer { }.whenever(encoder).run(any(), any())

        whenever(decoder.inputTensorCount).thenReturn(4)
        val inTensor = mock<Tensor>()
        whenever(decoder.getInputTensor(3)).thenReturn(inTensor)
        whenever(inTensor.numElements()).thenReturn(1)
        val outTensor = mock<Tensor>()
        whenever(decoder.getOutputTensor(1)).thenReturn(outTensor)
        whenever(outTensor.numElements()).thenReturn(1)

        var calls = 0
        doAnswer {
            val outputs = it.arguments[1] as MutableMap<Int, Any>
            val logits = outputs[0] as FloatArray
            if (calls == 0) {
                logits[123] = 1f
            } else {
                logits[1] = 1f
            }
            calls++
            null
        }.whenever(decoder).runForMultipleInputsOutputs(any(), any())

        setField(summarizer, "encoder", encoder)
        setField(summarizer, "decoder", decoder)
        setField(summarizer, "tokenizer", tokenizer)

        val result = summarizer.summarize("input text")
        assertEquals("mock summary", result)
        server.shutdown()
    }

    @Test
    fun summarizeFallsBackWhenTokenizerNativeMissing() = runBlocking {
        // Create dummy model files so fetcher doesn't attempt network
        val encFile = File(modelsDir, ModelFetcher.ENCODER_NAME).apply { writeBytes(byteArrayOf()) }
        val decFile = File(modelsDir, ModelFetcher.DECODER_NAME).apply { writeBytes(byteArrayOf()) }
        val spFile = File(modelsDir, ModelFetcher.SPIECE_NAME).apply { writeBytes(byteArrayOf()) }

        val fetcher = mock<ModelFetcher>()
        whenever(fetcher.ensureModels(any())).thenReturn(Triple(encFile, decFile, spFile))

        val tokenizer = mock<SentencePieceProcessor>()
        whenever(tokenizer.load(any())).thenThrow(UnsatisfiedLinkError("missing lib"))

        val summarizer = Summarizer(context, fetcher, spFactory = { tokenizer }, toast = { _, _ -> }, logger = { _, _ -> })

        val text = "One. Two. Three."
        val result = summarizer.summarize(text)
        assertEquals("One. Two", result)
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}

