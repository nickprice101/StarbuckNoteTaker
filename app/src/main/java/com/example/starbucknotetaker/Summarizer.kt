package com.example.starbucknotetaker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import com.example.starbucknotetaker.SentencePieceProcessor
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Simple on-device text summarizer.
 *
 * Downloads T5 encoder/decoder and SentencePiece tokenizer on demand and performs
 * greedy sequence-to-sequence inference. If anything fails it falls back to a
 * lightweight extractive strategy.
 */
class Summarizer(
    private val context: Context,
    private val fetcher: ModelFetcher = ModelFetcher()
) {
    private var encoder: Interpreter? = null
    private var decoder: Interpreter? = null
    private var tokenizer: SentencePieceProcessor? = null

    private suspend fun loadModelsIfNeeded() {
        if (encoder != null && decoder != null && tokenizer != null) return
        try {
            val (encFile, decFile, spFile) = fetcher.ensureModels(context)
            encoder = Interpreter(mapFile(encFile))
            decoder = Interpreter(mapFile(decFile))
            tokenizer = SentencePieceProcessor().apply { load(spFile.absolutePath) }
            showToast("AI summarizer loaded")
        } catch (e: Exception) {
            Log.e("Summarizer", "Failed to load models", e)
            showToast("Summarizer init failed: ${'$'}{e.message}", Toast.LENGTH_LONG)
            // leave interpreters null to trigger fallback
        }
    }

    private fun mapFile(file: File): MappedByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
    }

    /**
     * Generates a summary for the given [text]. Model inference runs on a background
     * dispatcher. If the models cannot be loaded, this falls back to a simple extractive
     * summary using the first couple of sentences.
     */
    suspend fun summarize(text: String): String = withContext(Dispatchers.Default) {
        loadModelsIfNeeded()
        val enc = encoder
        val dec = decoder
        val tok = tokenizer
        if (enc == null || dec == null || tok == null) {
            showToast("Using fallback summarization")
            return@withContext fallbackSummary(text)
        }

        val prefix = "summarize: "
        val inputIds = tok.encodeAsIds(prefix + text)
        val encLen = kotlin.math.min(inputIds.size, MAX_INPUT_TOKENS)
        val encInput = Array(1) { IntArray(MAX_INPUT_TOKENS) }
        for (i in 0 until encLen) encInput[0][i] = inputIds[i]
        val encLength = intArrayOf(encLen)
        val encOutShape = enc.getOutputTensor(0).shape()
        val encHidden = Array(encOutShape[0]) { Array(encOutShape[1]) { FloatArray(encOutShape[2]) } }
        enc.run(arrayOf(encInput, encLength), arrayOf(encHidden))

        val numInputs = dec.inputTensorCount
        val cache = Array(numInputs - 3) { FloatArray(dec.getInputTensor(it + 3).numElements()) }
        var token = START_TOKEN
        val result = mutableListOf<Int>()
        repeat(MAX_OUTPUT_TOKENS) {
            val inputs = arrayOfNulls<Any>(numInputs)
            inputs[0] = intArrayOf(token)
            inputs[1] = encHidden
            inputs[2] = encLength
            for (i in cache.indices) inputs[i + 3] = cache[i]

            val logits = FloatArray(VOCAB_SIZE)
            val outputs = HashMap<Int, Any>()
            outputs[0] = logits
            val newCache = Array(cache.size) { FloatArray(dec.getOutputTensor(it + 1).numElements()) }
            for (i in newCache.indices) outputs[i + 1] = newCache[i]
            dec.runForMultipleInputsOutputs(inputs, outputs)

            val next = argmax(logits)
            if (next == EOS_ID) return@repeat
            result.add(next)
            token = next
            for (i in cache.indices) cache[i] = newCache[i]
        }
        return@withContext tok.decodeIds(result.toIntArray())
    }

    fun fallbackSummary(text: String): String {
        val sentences = text.split('.', '!', '?')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val candidate = sentences.take(2).joinToString(". ")
        return if (candidate.isNotEmpty()) candidate else text.take(200)
    }

    /** Releases model and tokenizer resources. */
    fun close() {
        encoder?.close()
        decoder?.close()
        tokenizer?.close()
        encoder = null
        decoder = null
        tokenizer = null
    }

    private fun argmax(arr: FloatArray): Int {
        var maxIdx = 0
        var maxVal = arr[0]
        for (i in 1 until arr.size) {
            if (arr[i] > maxVal) {
                maxVal = arr[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    companion object {
        private const val MAX_INPUT_TOKENS = 256
        private const val MAX_OUTPUT_TOKENS = 64
        private const val START_TOKEN = 0
        private const val EOS_ID = 1
        private const val VOCAB_SIZE = 32128
    }

    private fun showToast(message: String, length: Int = Toast.LENGTH_SHORT) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, length).show()
        }
    }
}
