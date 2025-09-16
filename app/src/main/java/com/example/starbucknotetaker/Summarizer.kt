package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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
    private val fetcher: ModelFetcher = ModelFetcher(),
    private val spFactory: (Context) -> SentencePieceProcessor = { SentencePieceProcessor() },
    private val nativeLoader: (Context) -> Boolean = { NativeLibraryLoader.ensureTokenizer(it) },
    private val interpreterFactory: (MappedByteBuffer) -> LiteInterpreter = { TfLiteInterpreter.create(it) },
    private val logger: (String, Throwable) -> Unit = { msg, t -> Log.e("Summarizer", "summarizer: $msg", t) },
    private val debug: (String) -> Unit = { msg -> Log.d("Summarizer", "summarizer: $msg") }
) {
    private var encoder: LiteInterpreter? = null
    private var decoder: LiteInterpreter? = null
    private var tokenizer: SentencePieceProcessor? = null

    sealed class SummarizerState {
        object Loading : SummarizerState()
        object Ready : SummarizerState()
        object Fallback : SummarizerState()
        data class Error(val message: String) : SummarizerState()
    }

    private val _state = MutableStateFlow<SummarizerState>(SummarizerState.Ready)
    val state: StateFlow<SummarizerState> = _state

    private suspend fun loadModelsIfNeeded() {
        if (encoder != null && decoder != null && tokenizer != null) return
        debug("loading summarizer models")
        _state.emit(SummarizerState.Loading)
        when (val result = fetcher.ensureModels(context)) {
            is ModelFetcher.Result.Success -> {
                try {
                    if (!ensureNativeTokenizerLib()) {
                        logger(
                            "summarizer missing native tokenizer lib",
                            UnsatisfiedLinkError("libdjl_tokenizer.so not found")
                        )
                        _state.emit(SummarizerState.Fallback)
                        return
                    }
                    encoder = interpreterFactory(mapFile(result.encoder))
                    decoder = interpreterFactory(mapFile(result.decoder))
                    tokenizer = spFactory(context).apply { load(context, result.tokenizer.absolutePath) }
                    debug("summarizer models ready")
                    _state.emit(SummarizerState.Ready)
                } catch (e: Throwable) {
                    logger("summarizer failed to load models", e)
                    _state.emit(SummarizerState.Error(e.message ?: "Failed to load models"))
                }
            }
            is ModelFetcher.Result.Failure -> {
                logger("summarizer failed to fetch models", result.throwable ?: Exception(result.message))
                _state.emit(SummarizerState.Error(result.message))
            }
        }
        // leave interpreters null to trigger fallback
    }

    private fun mapFile(file: File): MappedByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
    }

    private var nativeTokenizerLoaded = false

    /**
     * Attempts to load the native SentencePiece tokenizer library.
     * Returns true if the library was loaded successfully.
     */
    private fun ensureNativeTokenizerLib(): Boolean {
        if (nativeTokenizerLoaded) return true
        val loaded = nativeLoader(context)
        nativeTokenizerLoaded = loaded
        return loaded
    }

    /**
     * Ensures the TensorFlow models and tokenizer are ready for inference.
     * Returns the resulting [SummarizerState] so callers can react to fallbacks
     * or failures when using the service through IPC.
     */
    suspend fun warmUp(): SummarizerState = withContext(Dispatchers.Default) {
        loadModelsIfNeeded()
        state.value
    }

    /**
     * Generates a summary for the given [text]. Model inference runs on a background
     * dispatcher. If the models cannot be loaded, this falls back to a simple extractive
     * summary using the first couple of sentences.
     */
    suspend fun summarize(text: String): String = withContext(Dispatchers.Default) {
        debug("summarizing text of length ${'$'}{text.length}")
        loadModelsIfNeeded()
        val enc = encoder
        val dec = decoder
        val tok = tokenizer
        if (enc == null || dec == null || tok == null) {
            debug("summarizer falling back")
            _state.emit(SummarizerState.Fallback)
            return@withContext fallbackSummary(text)
        }

        val prefix = "summarize: "
        val inputIds = tok.encodeAsIds(prefix + text)

        val encoderTokenCapacity = enc.getInputTensor(0).dimensionOrElse(1, MAX_INPUT_TOKENS)
        val encoderOutputTensor = enc.getOutputTensor(0)
        val encoderHiddenBatch = encoderOutputTensor.dimensionOrElse(0, 1)
        val encoderHiddenCapacity = encoderOutputTensor.dimensionOrElse(1, encoderTokenCapacity)
        val encoderHiddenSize = encoderOutputTensor.dimensionOrElse(2, ENCODER_HIDDEN_SIZE)
        val maxEncoderTokens = kotlin.math.min(encoderTokenCapacity, encoderHiddenCapacity)
        val encLen = kotlin.math.min(inputIds.size, maxEncoderTokens)
        val encInput = Array(1) { IntArray(encoderTokenCapacity) }
        for (i in 0 until encLen) encInput[0][i] = inputIds[i]
        val encLength = intArrayOf(encLen)
        val encHidden = Array(encoderHiddenBatch) {
            Array(encoderHiddenCapacity) { FloatArray(encoderHiddenSize) }
        }
        val encInputs = arrayOfNulls<Any>(2).apply {
            this[0] = encInput
            this[1] = encLength
        }
        val encOutputs = hashMapOf<Int, Any>(0 to encHidden)
        enc.runForMultipleInputsOutputs(encInputs, encOutputs)

        val numInputs = dec.inputTensorCount
        val cache = Array(numInputs - 3) {
            val tensor = dec.getInputTensor(it + 3)
            FloatArray(tensor.effectiveNumElements())
        }
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
            val newCache = Array(cache.size) {
                val tensor = dec.getOutputTensor(it + 1)
                FloatArray(tensor.effectiveNumElements())
            }
            for (i in newCache.indices) outputs[i + 1] = newCache[i]
            dec.runForMultipleInputsOutputs(inputs, outputs)

            val next = argmax(logits)
            if (next == EOS_ID) return@repeat
            result.add(next)
            token = next
            for (i in cache.indices) cache[i] = newCache[i]
        }
        val summary = tok.decodeIds(result.toIntArray())
        debug("summarizer inference complete")
        return@withContext summary
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

    private fun LiteTensor.dimensionOrElse(index: Int, fallback: Int): Int {
        val shape = shape()
        if (index in shape.indices && shape[index] > 0) return shape[index]
        val signature = shapeSignature()
        if (index in signature.indices && signature[index] > 0) return signature[index]
        return fallback
    }

    private fun LiteTensor.effectiveNumElements(): Int {
        val elements = numElements()
        if (elements > 0) return elements
        val shape = shape()
        val signature = shapeSignature()
        val dims = maxOf(shape.size, signature.size)
        if (dims == 0) return 1
        var total = 1
        for (i in 0 until dims) {
            val dim = when {
                i < shape.size && shape[i] > 0 -> shape[i]
                i < signature.size && signature[i] > 0 -> signature[i]
                else -> 1
            }
            total *= dim
        }
        return total
    }

    companion object {
        private const val MAX_INPUT_TOKENS = 256
        private const val MAX_OUTPUT_TOKENS = 64
        private const val START_TOKEN = 0
        private const val EOS_ID = 1
        private const val VOCAB_SIZE = 32128
        private const val ENCODER_HIDDEN_SIZE = 512
    }
}
