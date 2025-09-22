package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

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
    private val classifierFactory: (Context) -> NoteNatureClassifier = { NoteNatureClassifier() },
    private val logger: (String, Throwable) -> Unit = { msg, t -> Log.e("Summarizer", "summarizer: $msg", t) },
    private val debugSink: (String) -> Unit = { msg -> Log.d("Summarizer", "summarizer: $msg") },
    classifier: NoteNatureClassifier? = null,
) {
    private var encoder: LiteInterpreter? = null
    private var decoder: LiteInterpreter? = null
    private var tokenizer: SentencePieceProcessor? = null
    private var classifierRef: NoteNatureClassifier? = classifier

    sealed class SummarizerState {
        object Loading : SummarizerState()
        object Ready : SummarizerState()
        object Fallback : SummarizerState()
        data class Error(val message: String) : SummarizerState()
    }

    private val _state = MutableStateFlow<SummarizerState>(SummarizerState.Ready)
    val state: StateFlow<SummarizerState> = _state

    private val debugTrace = ThreadLocal<MutableList<String>?>()
    private val lastDebugTrace = AtomicReference<List<String>>(emptyList())

    private fun emitDebug(message: String) {
        debugTrace.get()?.add(message)
        debugSink(message)
    }

    private suspend fun loadModelsIfNeeded() {
        ensureClassifier()
        if (encoder != null && decoder != null && tokenizer != null) return
        emitDebug("loading summarizer models")
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
                    emitDebug("summarizer models ready")
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

    private fun ensureClassifier(): NoteNatureClassifier? {
        val existing = classifierRef
        if (existing != null) return existing
        return try {
            emitDebug("initializing note nature classifier")
            classifierFactory(context).also {
                classifierRef = it
                emitDebug("note nature classifier ready")
            }
        } catch (t: Throwable) {
            logger("failed to initialize note nature classifier", t)
            null
        }
    }

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

    fun consumeDebugTrace(): List<String> {
        return lastDebugTrace.getAndSet(emptyList())
    }

    /**
     * Generates a summary for the given [text]. Model inference runs on a background
     * dispatcher. If the models cannot be loaded, this falls back to a simple extractive
     * summary using the first couple of sentences.
     */
    suspend fun summarize(text: String): String = withContext(Dispatchers.Default) {
        val trace = mutableListOf<String>()
        debugTrace.set(trace)
        try {
            emitDebug("summarizing text of length ${text.length}")
            val classifierInstance = ensureClassifier()
            if (classifierInstance != null) {
                try {
                    val label = classifierInstance.classify(text, null)
                    emitDebug(
                        "classifier label=${label.type} confidence=${String.format(Locale.US, "%.3f", label.confidence)}"
                    )
                    if (label.humanReadable.isNotBlank() && label.confidence > 0.0) {
                        val trimmed = trimToWordLimit(label.humanReadable, CLASSIFIER_WORD_LIMIT)
                        emitDebug("classifier summary output: $trimmed")
                        _state.emit(SummarizerState.Ready)
                        return@withContext trimmed
                    }
                    emitDebug("classifier confidence insufficient; continuing with generative summary")
                } catch (t: Throwable) {
                    logger("summarizer classifier inference failed", t)
                    emitDebug("classifier failed, continuing with generative summary")
                }
            } else {
                emitDebug("classifier unavailable; continuing with generative summary")
            }

            loadModelsIfNeeded()
            val enc = encoder
            val dec = decoder
            val tok = tokenizer

            suspend fun fallback(reason: String, throwable: Throwable? = null): String {
                val label = classifyFallbackLabel(text, null)
                emitDebug("fallback reason: $reason; classifier=${label.type}")
                logger(reason, throwable ?: IllegalStateException(reason))
                _state.emit(SummarizerState.Fallback))
                return label.humanReadable
            }

            if (enc == null || dec == null || tok == null) {
                return@withContext fallback("models unavailable")
            }

            val prefix = "summarize: "
            val inputIds = tok.encodeAsIds(prefix + text)
            val sourceKeywords = buildKeywordStats(text)

            if (enc.inputTensorCount != 2) {
                return@withContext fallback("unexpected encoder input count: ${enc.inputTensorCount}")
            }

            val encoderAttentionTensor = enc.getInputTensor(0)
            val encoderInputTensor = enc.getInputTensor(1)
            val encoderBatch = encoderInputTensor.dimensionOrElse(0, 1)
            val attentionBatch = encoderAttentionTensor.dimensionOrElse(0, encoderBatch)
            if (encoderBatch != 1 || attentionBatch != 1) {
                return@withContext fallback("unsupported encoder batch size: ${encoderBatch}/${attentionBatch}")
            }

            val encoderTokenCapacity = encoderInputTensor.dimensionOrElse(1, MAX_INPUT_TOKENS)
            val encoderAttentionCapacity = encoderAttentionTensor.dimensionOrElse(1, encoderTokenCapacity)
            val encoderOutputTensor = enc.getOutputTensor(0)
            val encoderHiddenBatch = encoderOutputTensor.dimensionOrElse(0, 1)
            if (encoderHiddenBatch != 1) {
                return@withContext fallback("unsupported encoder hidden batch size: ${encoderHiddenBatch}")
            }
            val encoderHiddenCapacity = encoderOutputTensor.dimensionOrElse(1, encoderTokenCapacity)
            val encoderHiddenSize = encoderOutputTensor.dimensionOrElse(2, ENCODER_HIDDEN_SIZE)
            val maxEncoderTokens = kotlin.math.min(
                encoderTokenCapacity,
                kotlin.math.min(encoderAttentionCapacity, encoderHiddenCapacity)
            )
            val encLen = kotlin.math.min(inputIds.size, maxEncoderTokens)
            if (encLen == 0) {
                emitDebug("summarizer falling back due to empty encoder input")
                return@withContext fallback("encoder received empty input")
            }

            val encoderInput = Array(1) { IntArray(encoderTokenCapacity) }
            val encoderAttention = Array(1) { IntArray(encoderAttentionCapacity) }
            for (i in 0 until encLen) {
                encoderInput[0][i] = inputIds[i]
                encoderAttention[0][i] = 1
            }

            val encHidden = Array(encoderHiddenBatch) {
                Array(encoderHiddenCapacity) { FloatArray(encoderHiddenSize) }
            }
            val encoderInputs = arrayOfNulls<Any>(2).apply {
                this[0] = encoderAttention
                this[1] = encoderInput
            }
            val encOutputs = hashMapOf<Int, Any>(0 to encHidden)
            enc.runForMultipleInputsOutputs(encoderInputs, encOutputs)

            if (dec.inputTensorCount < 3) {
                return@withContext fallback("unexpected decoder input count: ${dec.inputTensorCount}")
            }

            val decoderAttentionTensor = dec.getInputTensor(0)
            val decoderTokenTensor = dec.getInputTensor(1)
            val decoderHiddenTensor = dec.getInputTensor(2)
            val decoderAttentionBatch = decoderAttentionTensor.dimensionOrElse(0, 1)
            val decoderTokenBatch = decoderTokenTensor.dimensionOrElse(0, 1)
            val decoderHiddenBatch = decoderHiddenTensor.dimensionOrElse(0, 1)
            if (decoderAttentionBatch != 1 || decoderTokenBatch != 1 || decoderHiddenBatch != 1) {
                return@withContext fallback(
                    "unsupported decoder batch sizes: ${decoderAttentionBatch}/${decoderTokenBatch}/${decoderHiddenBatch}"
                )
            }

            val decoderAttentionCapacity = decoderAttentionTensor.dimensionOrElse(1, maxEncoderTokens)
            val decoderTokenCapacity = decoderTokenTensor.dimensionOrElse(1, 1)
            val decoderHiddenCapacity = decoderHiddenTensor.dimensionOrElse(1, encoderHiddenCapacity)
            val decoderHiddenSize = decoderHiddenTensor.dimensionOrElse(2, encoderHiddenSize)
            if (encoderHiddenCapacity > decoderHiddenCapacity || encoderHiddenSize > decoderHiddenSize) {
                return@withContext fallback(
                    "decoder hidden state smaller than encoder output: ${encoderHiddenCapacity}x${encoderHiddenSize} vs ${decoderHiddenCapacity}x${decoderHiddenSize}"
                )
            }

            val decoderAttention = Array(1) { IntArray(decoderAttentionCapacity) }
            val decoderTokenInput = Array(1) { IntArray(decoderTokenCapacity) }
            val generatedTokens = mutableListOf<Int>()
            var currentToken = START_TOKEN
            val numInputs = dec.inputTensorCount
            val cache = Array(numInputs - 3) {
                val tensor = dec.getInputTensor(it + 3)
                FloatArray(tensor.effectiveNumElements())
            }
            val usesCache = cache.isNotEmpty() || decoderTokenCapacity == 1

            val decoderInputs = arrayOfNulls<Any>(numInputs).apply {
                this[0] = decoderAttention
                this[2] = encHidden
            }

            fun prepareDecoderInputs() {
                if (usesCache) {
                    decoderTokenInput[0].fill(0)
                    if (decoderTokenCapacity > 0) {
                        decoderTokenInput[0][0] = currentToken
                    }
                    val active = (generatedTokens.size + 1).coerceAtMost(decoderAttentionCapacity)
                    for (i in 0 until decoderAttentionCapacity) {
                        decoderAttention[0][i] = if (i < active) 1 else 0
                    }
                } else {
                    decoderTokenInput[0].fill(0)
                    decoderAttention[0].fill(0)
                    val totalTokens = 1 + generatedTokens.size
                    val copyLength = kotlin.math.min(totalTokens, decoderTokenCapacity)
                    if (decoderTokenCapacity > 0) {
                        decoderTokenInput[0][0] = START_TOKEN
                    }
                    for (i in 1 until copyLength) {
                        decoderTokenInput[0][i] = generatedTokens[i - 1]
                    }
                    val maskLength = kotlin.math.min(totalTokens, decoderAttentionCapacity)
                    for (i in 0 until maskLength) {
                        decoderAttention[0][i] = 1
                    }
                }
            }

            if (decoderTokenCapacity <= 0) {
                return@withContext fallback("decoder token tensor has no capacity")
            }
            if (decoderAttentionCapacity <= 0) {
                return@withContext fallback("decoder attention tensor has no capacity")
            }

            val result = mutableListOf<Int>()
            for (ignored in 0 until MAX_OUTPUT_TOKENS) {
                prepareDecoderInputs()
                decoderInputs[1] = decoderTokenInput
                for (i in cache.indices) decoderInputs[i + 3] = cache[i]

                val logits = Array(1) { Array(1) { FloatArray(VOCAB_SIZE) } }
                val outputs = HashMap<Int, Any>().apply {
                    this[0] = logits
                }
                val newCache = Array(cache.size) {
                    val tensor = dec.getOutputTensor(it + 1)
                    FloatArray(tensor.effectiveNumElements())
                }
                for (i in newCache.indices) outputs[i + 1] = newCache[i]

                dec.runForMultipleInputsOutputs(decoderInputs, outputs)

                val next = selectNextToken(logits[0][0], generatedTokens, tok, sourceKeywords)
                if (next == EOS_ID) break

                if (!usesCache) {
                    val requiredTokens = generatedTokens.size + 2
                    if (requiredTokens > decoderTokenCapacity) {
                        return@withContext fallback("decoder token buffer full")
                    }
                    if (requiredTokens > decoderAttentionCapacity) {
                        return@withContext fallback("decoder attention buffer full")
                    }
                }

                result.add(next)
                generatedTokens.add(next)
                currentToken = next
                for (i in cache.indices) cache[i] = newCache[i]
            }
            val decoded = tok.decodeIds(result.toIntArray())
            val cleaned = cleanSummary(decoded)
            if (cleaned.isEmpty()) {
                return@withContext fallback("empty summary output")
            }

            val summaryWords = tokenizeWords(cleaned)
                .map { normalizeWord(it) }
                .filter { it.isNotEmpty() }
            val overlapHits = sourceKeywords.scoreNewWords(summaryWords)
            val uniqueKeywords = sourceKeywords.uniqueCount()
            val requiredOverlap = when {
                uniqueKeywords == 0 -> 0
                uniqueKeywords == 1 -> 1
                uniqueKeywords <= 5 -> 1
                else -> 2
            }
            val hasLetterWord = summaryWords.any { containsLetter(it) }
            if (hasLetterWord && requiredOverlap > 0 && overlapHits < requiredOverlap) {
                val semanticScore = try {
                    val summaryIds = tok.encodeAsIds(cleaned)
                    val summaryLen = kotlin.math.min(summaryIds.size, maxEncoderTokens)
                    if (summaryLen == 0) {
                        emitDebug("semantic similarity check skipped due to empty summary encoding")
                        0f
                    } else {
                        val summaryInput = Array(1) { IntArray(encoderTokenCapacity) }
                        val summaryAttention = Array(1) { IntArray(encoderAttentionCapacity) }
                        for (i in 0 until summaryLen) {
                            summaryInput[0][i] = summaryIds[i]
                            summaryAttention[0][i] = 1
                        }
                        val summaryHidden = Array(encoderHiddenBatch) {
                            Array(encoderHiddenCapacity) { FloatArray(encoderHiddenSize) }
                        }
                        val summaryInputs = arrayOfNulls<Any>(2).apply {
                            this[0] = summaryAttention
                            this[1] = summaryInput
                        }
                        val summaryOutputs = hashMapOf<Int, Any>(0 to summaryHidden)
                        enc.runForMultipleInputsOutputs(summaryInputs, summaryOutputs)
                        cosineSimilarity(encHidden, encLen, summaryHidden, summaryLen)
                    }
                } catch (t: Throwable) {
                    logger("failed to compute semantic similarity", t)
                    Float.NaN
                }
                if (!semanticScore.isNaN() && semanticScore >= SEMANTIC_SIMILARITY_THRESHOLD) {
                    emitDebug("accepting abstractive summary due to semantic similarity ${semanticScore}")
                } else {
                    emitDebug(
                        "abstractive summary missing keyword overlap: ${overlapHits}/${requiredOverlap} semantic=${semanticScore}"
                    )
                    return@withContext fallback(
                        "abstractive summary missing keyword overlap (${overlapHits}/${requiredOverlap})"
                    )
                }
            }
            emitDebug("summarizer inference complete")
            return@withContext cleaned
        } finally {
            debugTrace.set(null)
            lastDebugTrace.set(trace.toList())
        }
    }

    fun fallbackSummary(text: String): String = fallbackSummary(text, null)

    fun fallbackSummary(text: String, event: NoteEvent?): String {
        val label = runBlocking { classifyFallbackLabel(text, event) }
        return label.humanReadable
    }

    private suspend fun classifyFallbackLabel(text: String, event: NoteEvent?): NoteNatureLabel {
        val classifier = ensureClassifier()
        val label = if (classifier != null) {
            classifier.classify(text, event)
        } else {
            emitDebug("fallback classifier unavailable; defaulting to general note label")
            NoteNatureLabel(
                NoteNatureType.GENERAL_NOTE,
                NoteNatureType.GENERAL_NOTE.humanReadable,
                0.0
            )
        }
        emitDebug("fallback classifier label: ${label.type} -> ${label.humanReadable}")
        return label
    }

    private fun selectNextToken(
        logits: FloatArray,
        generatedTokens: MutableList<Int>,
        tokenizer: SentencePieceProcessor,
        sourceKeywords: KeywordStats
    ): Int {
        val ranked = topKIndices(logits, MAX_TOKEN_CHOICES)
        if (ranked.isEmpty()) return argmax(logits)

        val preview = IntArray(generatedTokens.size + 1)
        for (i in generatedTokens.indices) preview[i] = generatedTokens[i]

        val useKeywordBias = generatedTokens.size >= MIN_TOKENS_FOR_KEYWORD_BIAS && !sourceKeywords.isEmpty()
        val existingWordSet: Set<String> = if (generatedTokens.isEmpty()) {
            emptySet()
        } else {
            val existingText = tokenizer.decodeIds(generatedTokens.toIntArray())
            tokenizeWords(existingText)
                .map { normalizeWord(it) }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        var firstValid: Int? = null
        var bestCandidate: Int? = null
        var bestScore = Int.MIN_VALUE
        var bestRank = Int.MAX_VALUE
        var eosCandidate: Int? = null
        var bestCandidateAddsLetters = false

        for ((rankIndex, candidate) in ranked.withIndex()) {
            if (candidate == EOS_ID) {
                if (!useKeywordBias) {
                    return candidate
                }
                eosCandidate = candidate
                continue
            }
            preview[preview.lastIndex] = candidate
            val previewText = tokenizer.decodeIds(preview)
            val words = tokenizeWords(previewText)
            if (words.isEmpty()) {
                if (firstValid == null) firstValid = candidate
                if (!useKeywordBias) {
                    return candidate
                }
                if (bestCandidate == null || rankIndex < bestRank) {
                    bestCandidate = candidate
                    bestScore = 0
                    bestRank = rankIndex
                }
                continue
            }
            if (isDegenerate(words)) {
                emitDebug("skipping token $candidate due to degeneracy")
                continue
            }
            val normalizedWords = words.map { normalizeWord(it) }.filter { it.isNotEmpty() }
            if (firstValid == null) firstValid = candidate
            if (!useKeywordBias) {
                return candidate
            }
            if (normalizedWords.isEmpty()) {
                if (bestCandidate == null || rankIndex < bestRank) {
                    bestCandidate = candidate
                    bestScore = 0
                    bestRank = rankIndex
                    bestCandidateAddsLetters = false
                }
                continue
            }
            val newWords = normalizedWords.filter { it !in existingWordSet }
            val overlapScore = sourceKeywords.scoreNewWords(newWords)
            val candidateAddsLetters = newWords.any { containsLetter(it) }
            if (
                overlapScore > bestScore ||
                (overlapScore == bestScore && !bestCandidateAddsLetters && candidateAddsLetters) ||
                (overlapScore == bestScore && candidateAddsLetters == bestCandidateAddsLetters && rankIndex < bestRank)
            ) {
                bestScore = overlapScore
                bestRank = rankIndex
                bestCandidate = candidate
                bestCandidateAddsLetters = candidateAddsLetters
            }
        }

        if (!useKeywordBias) {
            return firstValid ?: eosCandidate ?: ranked[0]
        }

        if (bestCandidate == null) {
            return firstValid ?: eosCandidate ?: ranked[0]
        }

        if (bestScore <= 0 && !bestCandidateAddsLetters && eosCandidate != null) {
            return eosCandidate
        }

        return bestCandidate ?: firstValid ?: eosCandidate ?: ranked[0]
    }

    private fun cleanSummary(rawSummary: String): String {
        val trimmed = rawSummary.trim()
        if (trimmed.isEmpty()) return ""
        val words = tokenizeWords(trimmed)
        if (words.isEmpty()) return trimmed

        val cleanedWords = mutableListOf<String>()
        var lastWord: String? = null
        var runLength = 0
        for (word in words) {
            if (lastWord != null && word.equals(lastWord, ignoreCase = true)) {
                runLength++
                if (runLength >= MAX_REPEAT_WORD_RUN) {
                    continue
                }
            } else {
                lastWord = word
                runLength = 1
            }
            cleanedWords.add(word)
        }

        if (cleanedWords.isEmpty()) return ""
        val collapsed = cleanedWords.joinToString(" ").trim()
        if (collapsed.isEmpty()) return ""

        val stopWordCount = cleanedWords.count { STOP_WORDS.contains(normalizeWord(it)) }
        val hasSentencePunctuation = collapsed.any { it == '.' || it == '!' || it == '?' }
        if (hasSentencePunctuation || stopWordCount > 0) {
            return collapsed
        }

        if (looksLikeConciseHeadline(cleanedWords) || looksLikeKeywordList(cleanedWords)) {
            return collapsed
        }

        val hasLetterWord = cleanedWords.any { containsLetter(it) }
        if (!hasLetterWord) {
            return ensureSentence(collapsed)
        }

        return sentenceCase(collapsed)
    }

    private fun looksLikeConciseHeadline(words: List<String>): Boolean {
        if (words.isEmpty() || words.size > MAX_HEADLINE_WORDS) return false

        var hasLetterWord = false
        var hasLowercaseWord = false
        var hasHeadlineCue = false
        for (word in words) {
            if (!hasLetterWord && containsLetter(word)) {
                hasLetterWord = true
            }
            if (!hasLowercaseWord && word.any { it.isLowerCase() }) {
                hasLowercaseWord = true
            }
            if (!hasHeadlineCue && isHeadlineCueWord(word)) {
                hasHeadlineCue = true
            }
            if (hasLetterWord && hasLowercaseWord && hasHeadlineCue) {
                break
            }
        }

        return hasLetterWord && hasLowercaseWord && hasHeadlineCue
    }

    private fun isHeadlineCueWord(word: String): Boolean {
        if (word.isEmpty()) return false
        if (word.any { it.isDigit() }) return true
        if (word.length <= HEADLINE_SHORT_WORD_MAX_LENGTH) return true

        val normalized = normalizeWord(word)
        if (normalized.isEmpty()) return false

        if (normalized.endsWith("ed")) return true
        if (normalized.endsWith("ing")) return true
        if (normalized.endsWith("s") && normalized.length <= HEADLINE_VERB_LENGTH_LIMIT) {
            if (!normalized.endsWith("es")) return true
            if (normalized.length <= HEADLINE_SHORT_VERB_LENGTH_LIMIT) return true
        }

        return false
    }

    private fun looksLikeKeywordList(words: List<String>): Boolean {
        if (words.size < 2 || words.size > MAX_HEADLINE_WORDS) return false

        val uniqueNormalized = LinkedHashSet<String>()
        var hasLetterWord = false

        for (word in words) {
            val normalized = normalizeWord(word)
            if (normalized.isEmpty()) return false
            if (normalized.any { it.isLetter() }) {
                hasLetterWord = true
            }
            uniqueNormalized.add(normalized)
        }

        if (!hasLetterWord) return false

        return uniqueNormalized.size >= 2
    }

    private fun ensureSentence(text: String): String {
        val capitalized = sentenceCase(text)
        if (capitalized.isEmpty()) return ""
        return if (capitalized.last() in SENTENCE_ENDINGS) capitalized else "$capitalized."
    }

    private fun sentenceCase(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
        }
    }

    private fun buildKeywordStats(text: String): KeywordStats {
        if (text.isEmpty()) return KeywordStats.EMPTY
        val counts = mutableMapOf<String, Int>()
        val words = tokenizeWords(text)
        for (word in words) {
            val normalized = normalizeWord(word)
            if (normalized.isEmpty()) continue
            for (variant in KeywordStats.expandVariants(normalized)) {
                if (variant.isEmpty() || STOP_WORDS.contains(variant)) continue
                val canonical = KeywordStats.canonicalize(variant)
                if (canonical.isEmpty()) continue
                counts[canonical] = counts.getOrDefault(canonical, 0) + 1
            }
        }
        if (counts.isEmpty()) return KeywordStats.EMPTY
        return KeywordStats(counts)
    }

    private fun normalizeWord(word: String): String {
        if (word.isEmpty()) return ""
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return ""
        val normalizedQuotes = trimmed.replace('’', '\'')
        val stripped = normalizedQuotes.trim {
            !it.isLetterOrDigit() && it != '\'' && !KeywordStats.isDash(it)
        }
        if (stripped.isEmpty()) return ""
        return stripped.lowercase(Locale.US)
    }

    private fun containsLetter(word: String): Boolean {
        if (word.isEmpty()) return false
        for (ch in word) {
            if (ch.isLetter()) return true
        }
        return false
    }

    private fun tokenizeWords(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return WORD_SPLIT_REGEX.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun isDegenerate(words: List<String>): Boolean {
        if (words.isEmpty()) return false
        var runLength = 1
        for (i in 1 until words.size) {
            if (words[i].equals(words[i - 1], ignoreCase = true)) {
                runLength++
                if (runLength >= MAX_REPEAT_WORD_RUN) {
                    return true
                }
            } else {
                runLength = 1
            }
        }

        if (words.size >= MIN_WORDS_FOR_UNIQUENESS) {
            val uniqueWords = words.map { it.lowercase(Locale.US) }.toSet()
            if (uniqueWords.size <= MIN_UNIQUE_WORDS) {
                return true
            }
        }
        return false
    }

    private fun cosineSimilarity(
        sourceHidden: Array<Array<FloatArray>>,
        sourceLength: Int,
        summaryHidden: Array<Array<FloatArray>>,
        summaryLength: Int
    ): Float {
        if (sourceLength <= 0 || summaryLength <= 0) return 0f
        if (sourceHidden.isEmpty() || sourceHidden[0].isEmpty()) return 0f
        if (summaryHidden.isEmpty() || summaryHidden[0].isEmpty()) return 0f

        val sourceLimit = sourceLength.coerceAtMost(sourceHidden[0].size)
        val summaryLimit = summaryLength.coerceAtMost(summaryHidden[0].size)
        if (sourceLimit <= 0 || summaryLimit <= 0) return 0f

        val sourceDim = if (sourceHidden[0].isNotEmpty()) sourceHidden[0][0].size else 0
        val summaryDim = if (summaryHidden[0].isNotEmpty()) summaryHidden[0][0].size else 0
        val dim = kotlin.math.min(sourceDim, summaryDim)
        if (dim <= 0) return 0f

        val sourceVector = FloatArray(dim)
        val summaryVector = FloatArray(dim)

        for (i in 0 until sourceLimit) {
            val row = sourceHidden[0][i]
            for (d in 0 until dim.coerceAtMost(row.size)) {
                sourceVector[d] += row[d]
            }
        }

        for (i in 0 until summaryLimit) {
            val row = summaryHidden[0][i]
            for (d in 0 until dim.coerceAtMost(row.size)) {
                summaryVector[d] += row[d]
            }
        }

        val invSource = 1f / sourceLimit
        val invSummary = 1f / summaryLimit
        var dot = 0f
        var sourceNorm = 0f
        var summaryNorm = 0f
        for (d in 0 until dim) {
            val pooledSource = sourceVector[d] * invSource
            val pooledSummary = summaryVector[d] * invSummary
            dot += pooledSource * pooledSummary
            sourceNorm += pooledSource * pooledSource
            summaryNorm += pooledSummary * pooledSummary
        }

        val denom = sqrt(sourceNorm) * sqrt(summaryNorm)
        if (denom == 0f) return 0f
        return dot / denom
    }

    private fun topKIndices(values: FloatArray, k: Int): IntArray {
        if (k <= 0) return intArrayOf()
        val limit = kotlin.math.min(k, values.size)
        val indices = IntArray(limit) { -1 }
        val scores = FloatArray(limit) { Float.NEGATIVE_INFINITY }
        for (i in values.indices) {
            val value = values[i]
            for (pos in 0 until limit) {
                if (value > scores[pos]) {
                    for (shift in limit - 1 downTo pos + 1) {
                        scores[shift] = scores[shift - 1]
                        indices[shift] = indices[shift - 1]
                    }
                    scores[pos] = value
                    indices[pos] = i
                    break
                }
            }
        }
        return indices.filter { it >= 0 }.toIntArray()
    }

    /** Releases model and tokenizer resources. */
    fun close() {
        encoder?.close()
        decoder?.close()
        tokenizer?.close()
        val closableClassifier = classifierRef as? AutoCloseable
        if (closableClassifier != null) {
            try {
                closableClassifier.close()
            } catch (t: Throwable) {
                logger("failed to close note nature classifier", t)
            }
        }
        encoder = null
        decoder = null
        tokenizer = null
        classifierRef = null
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

    private data class KeywordStats(val counts: Map<String, Int>) {
        fun scoreNewWords(words: List<String>): Int {
            if (counts.isEmpty()) return 0
            var score = 0
            val used = HashMap<String, Int>()
            for (word in words) {
                if (word.isEmpty()) continue
                val variants = expandVariants(word)
                for (variant in variants) {
                    val canonical = canonicalize(variant)
                    if (canonical.isEmpty()) continue
                    val limit = counts[canonical] ?: continue
                    val consumed = used.getOrElse(canonical) { 0 }
                    if (consumed < limit) {
                        used[canonical] = consumed + 1
                        score++
                        break
                    }
                }
            }
            return score
        }

        fun isEmpty(): Boolean = counts.isEmpty()

        fun uniqueCount(): Int = counts.size

        companion object {
            val EMPTY = KeywordStats(emptyMap())

            private val DASH_REGEX = Regex("[-‐‑–—‒−]+")

            fun canonicalize(word: String): String {
                if (word.isEmpty()) return ""
                var result = word

                result = result.replace('’', '\'')
                result = DASH_REGEX.replace(result, "")
                result = result.replace("'", "")

                if (result.length > 4 && result.endsWith("ies")) {
                    result = result.dropLast(3) + "y"
                } else {
                    if (result.length > 5 && result.endsWith("ing")) {
                        result = result.dropLast(3)
                    }
                    if (result.length > 4 && result.endsWith("ed")) {
                        result = result.dropLast(2)
                    }
                }

                if (result.length > 4 && result.endsWith("ly")) {
                    result = result.dropLast(2)
                }

                if (result.length > 4 && result.endsWith("es")) {
                    result = result.dropLast(2)
                } else if (result.length > 4 && result.endsWith("s") && !result.endsWith("ss")) {
                    result = result.dropLast(1)
                }

                if (result.length > 4 && result.endsWith("e") && !result.endsWith("ee")) {
                    result = result.dropLast(1)
                }

                if (result.length > 3) {
                    val last = result.last()
                    val prev = result.getOrNull(result.lastIndex - 1)
                    if (prev != null && prev == last && last.isLetter()) {
                        result = result.dropLast(1)
                    }
                }

                if (result.isEmpty()) return word
                return result
            }

            fun expandVariants(word: String): List<String> {
                if (word.isEmpty()) return emptyList()
                val normalized = word.replace('’', '\'')
                val variants = LinkedHashSet<String>()
                variants.add(normalized)

                val pieces = DASH_REGEX.split(normalized)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (pieces.size > 1) {
                    for (piece in pieces) {
                        variants.add(piece)
                    }
                    variants.add(pieces.joinToString(separator = ""))
                }

                val withoutApostrophes = normalized.replace("'", "")
                if (withoutApostrophes.isNotEmpty()) {
                    variants.add(withoutApostrophes)
                }

                return variants.toList()
            }

            fun isDash(char: Char): Boolean {
                return DASH_REGEX.matches(char.toString())
            }
        }
    }

    companion object {
        private const val MAX_INPUT_TOKENS = 256
        private const val MAX_OUTPUT_TOKENS = 64
        private const val START_TOKEN = 0
        private const val EOS_ID = 1
        private const val VOCAB_SIZE = 32128
        private const val MAX_TOKEN_CHOICES = 8
        private const val CLASSIFIER_WORD_LIMIT = 15
        private const val ELLIPSIS = "…"
        internal val WORD_SPLIT_REGEX = Regex("\\s+")
        internal fun trimToWordLimit(text: String, wordLimit: Int = CLASSIFIER_WORD_LIMIT): String {
            if (wordLimit <= 0) return ""
            val normalized = text.trim()
            if (normalized.isEmpty()) return normalized
            val words = WORD_SPLIT_REGEX.split(normalized).filter { it.isNotEmpty() }
            if (words.size <= wordLimit) {
                return words.joinToString(" ")
            }
            val trimmedWords = words.take(wordLimit)
            val candidate = trimmedWords.joinToString(" ")
            return candidate + ELLIPSIS
        }

        internal fun wordCount(text: String): Int {
            if (text.isBlank()) return 0
            return WORD_SPLIT_REGEX.split(text.trim()).count { it.isNotEmpty() }
        }
        private const val MAX_REPEAT_WORD_RUN = 2
        private const val MIN_WORDS_FOR_UNIQUENESS = 6
        private const val MIN_UNIQUE_WORDS = 3
        private const val ENCODER_HIDDEN_SIZE = 512
        private const val MIN_TOKENS_FOR_KEYWORD_BIAS = 2
        private const val MAX_HEADLINE_WORDS = 8
        private const val HEADLINE_SHORT_WORD_MAX_LENGTH = 4
        private const val HEADLINE_VERB_LENGTH_LIMIT = 8
        private const val HEADLINE_SHORT_VERB_LENGTH_LIMIT = 6
        private const val SEMANTIC_SIMILARITY_THRESHOLD = 0.7f
        private val STOP_WORDS = setOf(
            "a",
            "an",
            "the",
            "and",
            "or",
            "but",
            "if",
            "in",
            "on",
            "with",
            "for",
            "to",
            "of",
            "is",
            "are",
            "was",
            "were",
            "be",
            "been",
            "being",
            "this",
            "that",
            "these",
            "those",
            "it",
            "its",
            "as",
            "at",
            "by",
            "from",
            "about",
            "into",
            "over",
            "after",
            "before",
            "up",
            "down",
            "out",
            "so",
            "than",
            "too",
            "very",
            "can",
            "will",
            "just"
        )
        private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?')
    }
}
