package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device summariser backed by [LlamaEngine] (LiteRT-LM + Qwen3 0.6B).
 *
 * Completed summaries always come from Qwen. When the model is unavailable or a
 * Qwen result fails grounding checks, callers receive a bounded plain-text
 * placeholder that is not represented as an AI-generated summary.
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
        throw UnsupportedOperationException("LiteInterpreter not used in LiteRT-LM path")
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

    private val engine by lazy { LlamaEngineProvider.acquire(context) }
    private val summaryCache by lazy { QwenSummaryCache(context) }
    private val summaryMutex = Mutex()

    /** Live streaming inference progress from the underlying [LlamaEngine]. */
    val inferenceProgress: StateFlow<LlamaEngine.InferenceProgress>
        get() = engine.progress

    private val lastDebugTrace = AtomicReference<List<String>>(emptyList())

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Warms up the Qwen engine. When the LiteRT-LM model is absent, this returns
     * [SummarizerState.Fallback] to signal that plain placeholders will be used.
     */
    suspend fun warmUp(): SummarizerState = withContext(Dispatchers.Default) {
        _state.emit(SummarizerState.Loading)
        try {
            val ready = engine.warmUp()
            _state.emit(if (ready) SummarizerState.Ready else SummarizerState.Fallback)
            debugSink("warmUp: Qwen ready=$ready")
        } catch (t: Throwable) {
            logger("warm up failed", t)
            _state.emit(SummarizerState.Error(t.message ?: "warmUp failed"))
        }
        state.value
    }

    /**
     * Generates a concise 1–3 line summary of [text].
     *
     * Uses Qwen when the model is available, otherwise returns a bounded plain preview.
     */
    suspend fun summarize(text: String): String = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext ""
        summaryMutex.withLock {
            val trace = mutableListOf<String>()
            try {
                summaryCache.get(trimmed)?.let { cached ->
                    _state.emit(SummarizerState.Ready)
                    trace += "Qwen summary cache hit"
                    lastDebugTrace.set(trace)
                    return@withLock cached
                }
                engine.agentUnavailableReason()?.let { unavailable ->
                    trace += "Qwen unavailable: $unavailable"
                    lastDebugTrace.set(trace)
                    _state.emit(SummarizerState.Fallback)
                    return@withLock smartTruncate(lightweightPreview(trimmed), MAX_SUMMARY_LENGTH)
                }

                _state.emit(SummarizerState.Loading)
                debugSink("summarize: Qwen input length=${trimmed.length}")
                val chunks = selectSummaryChunks(
                    NoteTextChunker.chunkForQwen(
                        text = trimmed,
                        maxChars = QWEN_SUMMARY_CHUNK_CHARS,
                        maxTokens = QWEN_SUMMARY_CHUNK_TOKENS,
                    ),
                )
                trace += "Qwen hierarchical chunks=${chunks.size}"
                val partials = chunks.mapIndexed { index, chunk ->
                    engine.summarise(
                        text = chunk,
                        taskId = "summary-part-${trimmed.hashCode()}-$index",
                    )
                }
                var result = if (partials.size == 1) {
                    partials.single()
                } else {
                    engine.summarise(
                        text = buildString {
                            appendLine(
                                "Combine these Qwen-generated section summaries into one final " +
                                    "category-aware two-line preview. Preserve exact facts.",
                            )
                            partials.forEachIndexed { index, partial ->
                                appendLine("Section ${index + 1}: $partial")
                            }
                        },
                        taskId = "summary-reduce-${trimmed.hashCode()}",
                    )
                }

                val unsupported = AiGroundingValidator.unsupportedFacts(trimmed, result)
                if (unsupported.isNotEmpty()) {
                    trace += "Qwen grounding repair: ${unsupported.joinToString()}"
                    result = engine.repairSummary(
                        source = summaryEvidence(trimmed, unsupported),
                        draft = result,
                        unsupportedFacts = unsupported,
                        taskId = "summary-repair-${trimmed.hashCode()}",
                    )
                }
                val remainingUnsupported = AiGroundingValidator.unsupportedFacts(trimmed, result)
                require(remainingUnsupported.isEmpty()) {
                    "Qwen summary contained unsupported facts: ${remainingUnsupported.joinToString()}"
                }
                require(result.isNotBlank()) { "Qwen summary was empty" }
                summaryCache.put(trimmed, result)
                _state.emit(SummarizerState.Ready)
                trace += "Qwen result: $result"
                lastDebugTrace.set(trace)
                result
            } catch (t: Throwable) {
                logger("Qwen summarize failed", t)
                trace += "fallback reason: ${t.message}"
                lastDebugTrace.set(trace)
                _state.emit(SummarizerState.Fallback)
                smartTruncate(lightweightPreview(trimmed), MAX_SUMMARY_LENGTH)
            }
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
                "AI model not available. Check Settings → AI model to download or troubleshoot."
            }
        }

    /** Returns a bounded plain preview (no model call). */
    suspend fun fallbackSummary(text: String): String = fallbackSummary(text, null)

    /** Returns a bounded plain preview (no model call). */
    suspend fun fallbackSummary(
        text: String,
        @Suppress("UNUSED_PARAMETER") event: NoteEvent?,
    ): String = withContext(Dispatchers.Default) {
        smartTruncate(lightweightPreview(text), MAX_SUMMARY_LENGTH)
    }

    /** Synchronous plain preview used as a placeholder until Qwen finishes. */
    fun quickFallbackSummary(text: String): String =
        smartTruncate(lightweightPreview(text), MAX_SUMMARY_LENGTH)

    /** Consumes and returns the debug trace from the last [summarize] call. */
    fun consumeDebugTrace(): List<String> = lastDebugTrace.getAndSet(emptyList())

    /** Releases the native model context and associated resources. */
    fun close() {
        LlamaEngineProvider.releaseAfterIdle()
    }

    private fun selectSummaryChunks(chunks: List<String>): List<String> {
        if (chunks.size <= MAX_QWEN_SUMMARY_CHUNKS) return chunks
        val middle = chunks.withIndex()
            .drop(1)
            .dropLast(1)
            .sortedByDescending { (_, chunk) -> summarySalience(chunk) }
            .take(MAX_QWEN_SUMMARY_CHUNKS - 2)
            .sortedBy(IndexedValue<String>::index)
            .map(IndexedValue<String>::value)
        return buildList {
            add(chunks.first())
            addAll(middle)
            add(chunks.last())
        }.distinct()
    }

    private fun summarySalience(chunk: String): Int {
        val lower = chunk.lowercase(Locale.US)
        val priorityTerms = SUMMARY_PRIORITY_TERMS.count { lower.contains(it) }
        val structuredLines = chunk.lines().count { line ->
            line.trimStart().let { it.startsWith("#") || it.startsWith("-") || it.startsWith("[") }
        }
        return priorityTerms * 5 + structuredLines * 2 +
            AiGroundingValidator.protectedFacts(chunk).size * 3
    }

    private fun summaryEvidence(source: String, facts: Set<String>): String {
        if (source.length <= SUMMARY_REPAIR_EVIDENCE_CHARS) return source
        val windows = facts.mapNotNull { fact ->
            val index = source.indexOf(fact, ignoreCase = true)
            if (index < 0) null else {
                val start = (index - SUMMARY_FACT_WINDOW_CHARS).coerceAtLeast(0)
                val end = (index + fact.length + SUMMARY_FACT_WINDOW_CHARS).coerceAtMost(source.length)
                source.substring(start, end)
            }
        }
        val head = source.take(SUMMARY_REPAIR_EVIDENCE_CHARS / 3)
        val tail = source.takeLast(SUMMARY_REPAIR_EVIDENCE_CHARS / 3)
        return (listOf(head) + windows + tail)
            .distinct()
            .joinToString("\n[...]\n")
            .take(SUMMARY_REPAIR_EVIDENCE_CHARS)
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
        internal val WHITESPACE_REGEX = Regex("\\s+")
        private val TITLE_PREFIX_REGEX = Regex("^\\s*Title:\\s*", RegexOption.IGNORE_CASE)
        private val SENTENCE_CAPTURE = Regex("([^.!?]+[.!?])")
        private val TOKEN_SPLIT_REGEX = Regex("\\s+")
        private val STRIP_PUNCTUATION_REGEX = Regex("[\\p{Punct}]")
        private const val MODEL_SEQUENCE_LENGTH = 120
        private const val QWEN_SUMMARY_CHUNK_CHARS = 3_000
        private const val QWEN_SUMMARY_CHUNK_TOKENS = 900
        private const val MAX_QWEN_SUMMARY_CHUNKS = 4
        private const val SUMMARY_REPAIR_EVIDENCE_CHARS = 3_000
        private const val SUMMARY_FACT_WINDOW_CHARS = 220
        private val SUMMARY_PRIORITY_TERMS = listOf(
            "action", "assigned", "deadline", "decided", "due", "follow-up", "important",
            "meeting", "must", "next", "reminder", "todo", "urgent",
        )

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
