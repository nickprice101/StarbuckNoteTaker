package com.example.starbucknotetaker

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * On-device LLM inference engine backed by llama.cpp via [LlamaJni].
 *
 * When the GGUF model is not yet downloaded, or when the native library is
 * unavailable, the engine transparently falls back to the rule-based
 * summariser heuristics used by the legacy TFLite path.
 *
 * Supported modes (see [Mode]):
 *  - [Mode.SUMMARISE] — concise 1–3 line note preview
 *  - [Mode.REWRITE]   — rewritten in a clean, professional style
 *  - [Mode.QUESTION]  — answer a free-form question based on optional context
 *
 * Thermal throttling:
 *  On API 31+ the engine monitors [PowerManager.thermalHeadroom] before each
 *  inference call.  If the device is throttled ([THERMAL_STATUS_SEVERE]) the
 *  thread count is halved and [InferenceProgress.Throttled] is emitted so the
 *  UI can surface a friendly warning.
 */
class LlamaEngine(private val context: Context) {

    // ------------------------------------------------------------------
    // Public types
    // ------------------------------------------------------------------

    enum class Mode { SUMMARISE, REWRITE, QUESTION }

    sealed class InferenceProgress {
        object Idle : InferenceProgress()
        /** Emitted periodically as tokens stream from the model. */
        data class Thinking(val partialText: String, val taskId: String) : InferenceProgress()
        /** Emitted when the device thermal headroom is low. */
        object Throttled : InferenceProgress()
        /** Emitted on successful completion. */
        data class Done(val taskId: String, val result: String) : InferenceProgress()
        /** Emitted on error. */
        data class Error(val taskId: String, val message: String) : InferenceProgress()
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private val _progress = MutableStateFlow<InferenceProgress>(InferenceProgress.Idle)
    val progress: StateFlow<InferenceProgress> = _progress

    private val inferenceMutex = Mutex()
    private var nativeHandle: Long = 0L
    private var currentThreads: Int = DEFAULT_THREADS
    private val jniAvailable: Boolean by lazy { LlamaJni.load(context) }
    private val modelManager = LlamaModelManager(context)
    val modelStatus: StateFlow<LlamaModelManager.ModelStatus> = modelManager.modelStatus

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Summarises [text] in 1–3 concise lines.
     *
     * Falls back to rule-based heuristics when the model is unavailable.
     */
    suspend fun summarise(text: String, taskId: String = newTaskId()): String =
        infer(Mode.SUMMARISE, text, taskId = taskId)

    /**
     * Rewrites [text] in a cleaner, more professional style.
     *
     * Falls back to returning the original text when the model is unavailable.
     */
    suspend fun rewrite(text: String, taskId: String = newTaskId()): String =
        infer(Mode.REWRITE, text, taskId = taskId)

    /**
     * Answers [question] using [context] as optional grounding material.
     *
     * Falls back to a polite "model not available" message when applicable.
     */
    suspend fun answer(
        question: String,
        context: String? = null,
        taskId: String = newTaskId(),
    ): String = infer(Mode.QUESTION, question, context, taskId)

    /** Releases the native model context and frees memory. */
    fun close() {
        val h = nativeHandle
        nativeHandle = 0L
        if (h != 0L && jniAvailable) {
            LlamaJni.nativeRelease(h)
        }
    }

    // ------------------------------------------------------------------
    // Private inference
    // ------------------------------------------------------------------

    private suspend fun infer(
        mode: Mode,
        primaryText: String,
        secondaryText: String? = null,
        taskId: String,
    ): String = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            checkThermalThrottle()
            val modelPath = modelManager.getModelPath()
            val useNative = jniAvailable && modelPath != null

            if (useNative) {
                runCatching {
                    ensureNativeHandle(modelPath!!)
                    generateWithNative(mode, primaryText, secondaryText, taskId)
                }.getOrElse { e ->
                    Log.e(TAG, "Native inference failed, falling back", e)
                    _progress.value = InferenceProgress.Error(taskId, e.message ?: "inference error")
                    fallback(mode, primaryText)
                }
            } else {
                fallback(mode, primaryText)
            }
        }
    }

    private fun ensureNativeHandle(modelPath: String) {
        if (nativeHandle != 0L) return
        nativeHandle = LlamaJni.nativeInit(modelPath, N_CTX, currentThreads)
        if (nativeHandle == 0L) {
            throw IllegalStateException("Failed to initialise llama.cpp model context")
        }
        Log.i(TAG, "Native handle initialised for $modelPath")
    }

    private fun generateWithNative(
        mode: Mode,
        primaryText: String,
        secondaryText: String?,
        taskId: String,
    ): String {
        val prompt = buildPrompt(mode, primaryText, secondaryText)
        val sb = StringBuilder()
        _progress.value = InferenceProgress.Thinking("", taskId)

        val result = LlamaJni.nativeGenerate(
            handle = nativeHandle,
            prompt = prompt,
            maxTokens = MAX_TOKENS,
            callback = LlamaJni.TokenCallback { token ->
                sb.append(token)
                _progress.value = InferenceProgress.Thinking(sb.toString(), taskId)
                true
            },
        )

        return if (result == LlamaJni.STUB_RESPONSE_MARKER) {
            Log.d(TAG, "Stub response received — using rule-based fallback")
            fallback(mode, primaryText)
        } else {
            val cleaned = result.trim()
            _progress.value = InferenceProgress.Done(taskId, cleaned)
            cleaned
        }
    }

    private fun fallback(mode: Mode, text: String): String = when (mode) {
        Mode.SUMMARISE -> Summarizer.lightweightPreview(text)
        Mode.REWRITE   -> text.trim()
        Mode.QUESTION  -> "AI model not yet downloaded. Download it in Settings to enable question answering."
    }

    // ------------------------------------------------------------------
    // Prompt construction
    // ------------------------------------------------------------------

    private fun buildPrompt(mode: Mode, primary: String, secondary: String?): String {
        val systemPrompt = when (mode) {
            Mode.SUMMARISE ->
                "You are a concise note-taking assistant. " +
                "Summarise the following note in at most 3 short lines. " +
                "Output only the summary — no preamble, no labels."
            Mode.REWRITE ->
                "You are a professional writing assistant. " +
                "Rewrite the following note in a cleaner, more professional style. " +
                "Preserve all facts. Output only the rewritten note."
            Mode.QUESTION ->
                if (secondary != null) {
                    "You are a helpful assistant. " +
                    "Using the provided context, answer the question concisely."
                } else {
                    "You are a helpful assistant. Answer the question concisely."
                }
        }

        return buildString {
            append("<|system|>\n$systemPrompt\n<|end|>\n")
            if (mode == Mode.QUESTION && secondary != null) {
                append("<|user|>\nContext:\n${secondary.take(MAX_CONTEXT_CHARS)}\n\nQuestion: $primary\n<|end|>\n")
            } else {
                append("<|user|>\n${primary.take(MAX_CONTEXT_CHARS)}\n<|end|>\n")
            }
            append("<|assistant|>\n")
        }
    }

    // ------------------------------------------------------------------
    // Thermal throttle detection
    // ------------------------------------------------------------------

    private fun checkThermalThrottle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val headroom = pm.thermalHeadroom(1)
                if (headroom < THERMAL_HEADROOM_THROTTLE_THRESHOLD) {
                    Log.w(TAG, "Thermal headroom low ($headroom) — reducing thread count")
                    currentThreads = maxOf(1, currentThreads / 2)
                    // Release native handle so it is re-created with new thread count
                    if (nativeHandle != 0L && jniAvailable) {
                        LlamaJni.nativeRelease(nativeHandle)
                        nativeHandle = 0L
                    }
                    _progress.value = InferenceProgress.Throttled
                } else if (currentThreads < DEFAULT_THREADS) {
                    currentThreads = DEFAULT_THREADS
                }
            } else {
                @Suppress("DEPRECATION")
                val status = pm.currentThermalStatus
                if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                    Log.w(TAG, "Thermal status severe — reducing thread count")
                    currentThreads = maxOf(1, currentThreads / 2)
                    if (nativeHandle != 0L && jniAvailable) {
                        LlamaJni.nativeRelease(nativeHandle)
                        nativeHandle = 0L
                    }
                    _progress.value = InferenceProgress.Throttled
                } else if (currentThreads < DEFAULT_THREADS) {
                    currentThreads = DEFAULT_THREADS
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Thermal check failed", e)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newTaskId(): String = java.util.UUID.randomUUID().toString()

    companion object {
        private const val TAG = "LlamaEngine"
        private const val N_CTX = 2048
        private const val DEFAULT_THREADS = 4
        private const val MAX_TOKENS = 256
        private const val MAX_CONTEXT_CHARS = 2000
        // Below this headroom fraction the engine throttles thread count
        private const val THERMAL_HEADROOM_THROTTLE_THRESHOLD = 0.15f
    }
}
