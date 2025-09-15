package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.runBlocking
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
        ModelFetcher.ensureModels(context)

        assertTrue(File(modelsDir, ModelFetcher.ENCODER_NAME).exists())
        assertTrue(File(modelsDir, ModelFetcher.DECODER_NAME).exists())
        assertTrue(File(modelsDir, ModelFetcher.SPIECE_NAME).exists())

        val summarizer = Summarizer(context)

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
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}

