package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device summariser backed by [LlamaEngine] (MLC LLM + Llama 3.2 3B).
 *
 * When the MLC model has not been downloaded yet, or when the native library
 * is unavailable, all inference automatically falls back to the lightweight
 * rule-based heuristics defined in this class — behaviour identical to the
 * previous TFLite path.
 *
 * Callers that previously constructed [Summarizer] with a custom
 * [interpreterFactory] (e.g. in unit tests) can continue to do so; the
 * factory parameter is retained but ignored at runtime.  Tests that need
 * to exercise the fallback path independently can use the public static
 * helpers directly.
 *
 * New functionality added over the previous TFLite version:
 *  - [rewrite] — rewrites a note in a clean, professional style
 *  - [answer]  — answers a question using optional note context
 *  - [inferenceProgress] — [StateFlow] of streaming inference progress
 */
class Summarizer(
    private val context: Context,
    /** Retained for source compatibility with existing tests; not used at runtime. */
    @Suppress("UNUSED_PARAMETER")
    private val interpreterFactory: (java.nio.MappedByteBuffer) -> LiteInterpreter = {
        throw UnsupportedOperationException("LiteInterpreter not used in MLC path")
    },
    private val logger: (String, Throwable) -> Unit = { msg, t -> Log.e("Summarizer", msg, t) },
    private val debugSink: (String) -> Unit = { msg -> Log.d("Summarizer", msg) },
    /** Retained for source compatibility with existing tests; not used at runtime. */
    @Suppress("UNUSED_PARAMETER")
    private val assetLoader: (Context, String) -> java.io.InputStream = { ctx, name ->
        ctx.assets.open(name)
    },
) {

    // ------------------------------------------------------------------
    // Public state
    // ------------------------------------------------------------------

    sealed class SummarizerState {
        object Loading : SummarizerState()
        object Ready : SummarizerState()
        object Fallback : SummarizerState()
        data class Error(val message: String) : SummarizerState()
    }

    private val _state = MutableStateFlow<SummarizerState>(SummarizerState.Ready)
    val state: StateFlow<SummarizerState> = _state

    private val engine by lazy { LlamaEngine(context) }

    /** Live streaming inference progress from the underlying [LlamaEngine]. */
    val inferenceProgress: StateFlow<LlamaEngine.InferenceProgress>
        get() = engine.progress

    private val lastDebugTrace = AtomicReference<List<String>>(emptyList())

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Warms up the summariser engine. When the MLC model is present the
     * native context is initialised; otherwise the method returns [SummarizerState.Fallback]
     * to signal that heuristic summaries will be used.
     */
    suspend fun warmUp(): SummarizerState = withContext(Dispatchers.Default) {
        _state.emit(SummarizerState.Loading)
        try {
            val modelPath = engine.modelStatus.value
            val ready = modelPath is LlamaModelManager.ModelStatus.Present
            val next = if (ready) SummarizerState.Ready else SummarizerState.Fallback
            _state.emit(next)
            debugSink("warmUp: state=$next")
        } catch (t: Throwable) {
            logger("warm up failed", t)
            _state.emit(SummarizerState.Error(t.message ?: "warmUp failed"))
        }
        state.value
    }

    /**
     * Generates a concise 1–3 line summary of [text].
     *
     * Uses the LLM when the model is available, otherwise falls back to the
     * rule-based heuristic summariser.
     */
    suspend fun summarize(text: String): String = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext ""
        val trace = mutableListOf<String>()
        try {
            _state.emit(SummarizerState.Loading)
            debugSink("summarize: input length=${trimmed.length}")
            trace += "summarizing text of length ${trimmed.length}"
            val result = engine.summarise(trimmed)
            _state.emit(SummarizerState.Ready)
            trace += "result: $result"
            lastDebugTrace.set(trace)
            result
        } catch (t: Throwable) {
            logger("summarize failed", t)
            trace += "fallback reason: ${t.message}"
            lastDebugTrace.set(trace)
            _state.emit(SummarizerState.Fallback)
            fallbackSummaryInternal(trimmed)
        }
    }

    /**
     * Rewrites [text] in a cleaner, more professional style using the LLM.
     *
     * Falls back to returning the original text when the model is unavailable.
     */
    suspend fun rewrite(text: String): String = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext ""
        try {
            _state.emit(SummarizerState.Loading)
            val result = engine.rewrite(trimmed)
            _state.emit(SummarizerState.Ready)
            result
        } catch (t: Throwable) {
            logger("rewrite failed", t)
            _state.emit(SummarizerState.Fallback)
            trimmed
        }
    }

    /**
     * Answers [question] using optional [noteContext] as grounding material.
     *
     * Falls back to a descriptive message when the model is unavailable.
     */
    suspend fun answer(question: String, noteContext: String? = null): String =
        withContext(Dispatchers.Default) {
            val q = question.trim()
            if (q.isEmpty()) return@withContext ""
            try {
                _state.emit(SummarizerState.Loading)
                val result = engine.answer(q, noteContext)
                _state.emit(SummarizerState.Ready)
                result
            } catch (t: Throwable) {
                logger("answer failed", t)
                _state.emit(SummarizerState.Fallback)
                "AI model not yet downloaded. Download it in Settings to enable Q&A."
            }
        }

    /** Returns a rule-based heuristic summary (no LLM call). */
    suspend fun fallbackSummary(text: String): String = fallbackSummary(text, null)

    /** Returns a rule-based heuristic summary (no LLM call). */
    suspend fun fallbackSummary(
        text: String,
        @Suppress("UNUSED_PARAMETER") event: NoteEvent?,
    ): String = withContext(Dispatchers.Default) { fallbackSummaryInternal(text) }

    /** Synchronous rule-based preview (used as a placeholder until async result arrives). */
    fun quickFallbackSummary(text: String): String =
        smartTruncate(lightweightPreview(text), MAX_SUMMARY_LENGTH)

    /** Consumes and returns the debug trace from the last [summarize] call. */
    fun consumeDebugTrace(): List<String> = lastDebugTrace.getAndSet(emptyList())

    /** Releases the native model context and associated resources. */
    fun close() {
        engine.close()
    }

    // ------------------------------------------------------------------
    // Rule-based fallback logic
    // ------------------------------------------------------------------

    private fun fallbackSummaryInternal(text: String): String {
        val contentOnly = extractContentForFallback(text)
        val normalized = contentOnly.trim().replace(WHITESPACE_REGEX, " ")
        if (normalized.isEmpty()) return ""
        val truncated = normalized.take(FALLBACK_CHAR_LIMIT)
        return formatFallbackAcrossTwoLines(truncated)
    }

    private fun extractContentForFallback(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val separatorIndex = trimmed.indexOf("\n\n")
        if (separatorIndex >= 0 && separatorIndex + 2 < trimmed.length) {
            val afterSeparator = trimmed.substring(separatorIndex + 2).trim()
            if (afterSeparator.isNotEmpty()) return afterSeparator
        }
        if (trimmed.startsWith("Title:", ignoreCase = true)) {
            return trimmed.removePrefix("Title:").trim()
        }
        return trimmed
    }

    private fun formatFallbackAcrossTwoLines(truncated: String): String {
        if (truncated.isEmpty()) return ""
        if (truncated.length <= FALLBACK_FIRST_LINE_TARGET) return truncated
        val desiredBreak = minOf(truncated.length, FALLBACK_FIRST_LINE_TARGET)
        val whitespaceBreak = truncated.lastIndexOf(' ', desiredBreak)
        val breakIndex = when {
            whitespaceBreak in 0 until truncated.length - 1 -> whitespaceBreak + 1
            truncated.length > FALLBACK_FIRST_LINE_TARGET -> FALLBACK_FIRST_LINE_TARGET
            else -> truncated.length
        }
        if (breakIndex <= 0 || breakIndex >= truncated.length) return truncated
        return truncated.substring(0, breakIndex) + "\n" + truncated.substring(breakIndex)
    }

    // ------------------------------------------------------------------
    // Companion — static helpers kept for backward-compat with tests
    // and for use in fallback / preview paths across the app.
    // ------------------------------------------------------------------

    companion object {
        // Asset name constants retained for backward compatibility.
        internal const val MODELS_DIR_NAME            = "models"
        internal const val MODEL_ASSET_NAME           = "note_classifier.tflite"
        internal const val CATEGORY_MAPPING_ASSET_NAME = "category_mapping.json"
        internal const val TOKENIZER_VOCAB_ASSET_NAME  = "tokenizer_vocabulary_v2.txt"

        private const val MAX_SUMMARY_LENGTH = 140
        private const val MAX_PREVIEW_LENGTH = 160
        private const val FALLBACK_CHAR_LIMIT = 150
        private const val FALLBACK_FIRST_LINE_TARGET = FALLBACK_CHAR_LIMIT / 2
        internal val WHITESPACE_REGEX = Regex("\\s+")
        private val TITLE_PREFIX_REGEX = Regex("^\\s*Title:\\s*", RegexOption.IGNORE_CASE)
        private val SENTENCE_CAPTURE = Regex("([^.!?]+[.!?])")
        private val TOKEN_SPLIT_REGEX = Regex("\\s+")
        private val STRIP_PUNCTUATION_REGEX = Regex("[\\p{Punct}]")
        private const val MODEL_SEQUENCE_LENGTH = 120

        /**
         * Returns a lightweight heuristic preview of [text] (≤2 sentences).
         * Used as an immediate placeholder while async inference runs.
         */
        fun lightweightPreview(text: String): String {
            val normalized = text.trim().replace(WHITESPACE_REGEX, " ")
            if (normalized.isEmpty()) return ""
            val sentences = SENTENCE_CAPTURE.findAll(normalized).map { it.value.trim() }.toList()
            val preview = when {
                sentences.isEmpty() -> normalized
                sentences.size == 1 -> sentences.first()
                else -> sentences.take(2).joinToString(" ")
            }
            return smartTruncate(preview, MAX_PREVIEW_LENGTH)
        }

        /** Truncates [text] to [maxLength] preferring sentence then word boundaries. */
        fun smartTruncate(text: String, maxLength: Int = MAX_SUMMARY_LENGTH): String {
            val normalized = text.trim().replace(WHITESPACE_REGEX, " ")
            if (normalized.length <= maxLength) return normalized
            val truncated = normalized.substring(0, maxLength)
            val sentenceEnd = truncated.lastIndexOfAny(charArrayOf('.', '!', '?'))
            if (sentenceEnd >= (maxLength * 0.7).toInt()) {
                return truncated.substring(0, sentenceEnd + 1).trim()
            }
            val lastSpace = truncated.lastIndexOf(' ')
            if (lastSpace > 0) return truncated.substring(0, lastSpace).trim()
            return truncated.trim()
        }

        /**
         * Normalises a note source string before it is sent to the model.
         * "Title: foo\n\nbar" → "foo: bar"
         */
        fun normalizeForModelInput(text: String): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return ""
            val separatorIndex = trimmed.indexOf("\n\n")
            if (separatorIndex >= 0) {
                val header = trimmed.substring(0, separatorIndex).trim()
                val body = trimmed.substring(separatorIndex + 2).trim()
                if (header.startsWith("Title:", ignoreCase = true) && body.isNotEmpty()) {
                    val title = TITLE_PREFIX_REGEX.replace(header, "").trim()
                    if (title.isNotEmpty()) {
                        return "$title: ${body.replace(WHITESPACE_REGEX, " ")}".trim()
                    }
                    return body.replace(WHITESPACE_REGEX, " ").trim()
                }
            }
            if (trimmed.startsWith("Title:", ignoreCase = true)) {
                return TITLE_PREFIX_REGEX.replace(trimmed, "").replace(WHITESPACE_REGEX, " ").trim()
            }
            return trimmed.replace(WHITESPACE_REGEX, " ").trim()
        }

        /**
         * Tokenises [text] against [vocabulary] into a fixed-length int array.
         * Retained for backward compatibility with unit tests.
         */
        internal fun tokenizeForModelInput(text: String, vocabulary: TokenizerVocabulary): IntArray {
            val normalized = normalizeForModelInput(text).lowercase(Locale.US)
            val stripped = STRIP_PUNCTUATION_REGEX.replace(normalized, " ")
            val tokens = TOKEN_SPLIT_REGEX.split(stripped).filter { it.isNotBlank() }
            val output = IntArray(MODEL_SEQUENCE_LENGTH)
            var index = 0
            for (token in tokens) {
                if (index >= MODEL_SEQUENCE_LENGTH) break
                output[index] = vocabulary.tokenToId(token)
                index++
            }
            return output
        }
    }
}

// --------------------------------------------------------------------------
// TokenizerVocabulary — kept for backward compatibility with existing tests
// --------------------------------------------------------------------------

internal class TokenizerVocabulary private constructor(
    private val tokenToIndex: Map<String, Int>,
    private val unknownTokenId: Int,
) {
    fun tokenToId(token: String): Int = tokenToIndex[token] ?: unknownTokenId

    companion object {
        val EMPTY: TokenizerVocabulary = from(emptyList())
        private const val UNKNOWN_TOKEN = "[UNK]"

        fun from(tokens: List<String>): TokenizerVocabulary {
            val map = LinkedHashMap<String, Int>(tokens.size)
            tokens.forEachIndexed { index, token ->
                map.putIfAbsent(token, index)
            }
            val unknownId = map[UNKNOWN_TOKEN]
                ?: map["[unk]"]
                ?: map["UNK"]
                ?: 1
            return TokenizerVocabulary(map, unknownId)
        }
    }
}
