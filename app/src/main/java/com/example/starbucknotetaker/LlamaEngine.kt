package com.example.starbucknotetaker

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Shared on-device language-model facade backed by Google's LiteRT-LM Android
 * runtime. The portable Qwen3 model runs with GPU acceleration on ARM64 phones
 * and the CPU backend on x86_64 emulators.
 *
 * Engine initialization loads the model but deliberately does not generate a
 * priming token. LiteRT-LM owns kernel/cache initialization and persists its
 * optimized cache in [Context.getCacheDir], avoiding the old failure mode where
 * an unbounded startup prime held the only inference lock.
 */
class LlamaEngine(private val context: Context) {

    enum class Mode { SUMMARISE, REWRITE, QUESTION }

    data class ChatMessage(val role: String, val content: String)

    class ModelUnavailableException(message: String) : IllegalStateException(message)

    sealed class InferenceProgress {
        object Idle : InferenceProgress()
        data class Thinking(val partialText: String, val taskId: String) : InferenceProgress()
        object Throttled : InferenceProgress()
        data class Done(val taskId: String, val result: String) : InferenceProgress()
        data class Error(val taskId: String, val message: String) : InferenceProgress()
    }

    private val _progress = MutableStateFlow<InferenceProgress>(InferenceProgress.Idle)
    val progress: StateFlow<InferenceProgress> = _progress

    private val inferenceMutex = Mutex()
    private val engineStateLock = Any()
    private val modelManager = LlamaModelManager(context)
    private var liteRtEngine: Engine? = null
    private var activeBackend: String? = null

    val modelStatus: StateFlow<LlamaModelManager.ModelStatus> = modelManager.modelStatus

    val isWarm: Boolean
        get() = synchronized(engineStateLock) { liteRtEngine?.isInitialized() == true }

    suspend fun summarise(
        text: String,
        taskId: String = newTaskId(),
        maxTokensOverride: Int? = null,
    ): String = infer(Mode.SUMMARISE, text, taskId = taskId, maxTokensOverride = maxTokensOverride)

    suspend fun rewrite(
        text: String,
        taskId: String = newTaskId(),
        maxTokensOverride: Int? = null,
    ): String = infer(Mode.REWRITE, text, taskId = taskId, maxTokensOverride = maxTokensOverride)

    suspend fun answer(
        question: String,
        context: String? = null,
        taskId: String = newTaskId(),
        maxTokensOverride: Int? = null,
    ): String = infer(Mode.QUESTION, question, context, taskId, maxTokensOverride)

    /** Completes an ADK-owned turn using real local model output. */
    suspend fun completeChat(
        messages: List<ChatMessage>,
        taskId: String = newTaskId(),
        maxTokens: Int = AGENT_MAX_OUTPUT_TOKENS,
        temperature: Float = 0.25f,
        topP: Float = 0.9f,
        onToken: (String) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            agentUnavailableReason()?.let { throw ModelUnavailableException(it) }
            val modelPath = modelManager.getModelPath()
                ?: throw ModelUnavailableException("Download the on-device AI model in Settings first.")
            checkThermalThrottle()
            try {
                ensureEngineLoaded(modelPath)
                generate(
                    messages = messages,
                    taskId = taskId,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                    timeoutMs = AGENT_GENERATION_TIMEOUT_MS,
                    onToken = onToken,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "LiteRT-LM ADK turn failed taskId=$taskId", failure)
                _progress.value = InferenceProgress.Error(
                    taskId,
                    failure.message ?: "On-device agent inference failed",
                )
                throw failure
            }
        }
    }

    fun agentUnavailableReason(): String? {
        if (!DeviceCapabilityChecker.isAiCapable(context)) {
            return "This device needs at least 4 GB RAM to run the on-device AI model."
        }
        val status = modelManager.modelStatus.value
        if (status is LlamaModelManager.ModelStatus.Unsupported) return status.message
        if (modelManager.getModelPath() == null) {
            return "Download the on-device AI model in Settings before using the agent."
        }
        return null
    }

    val agentPromptCharLimit: Int
        get() = if (isCompactEmulatorProfile()) {
            AGENT_PROMPT_CHARS_EMULATOR
        } else {
            AGENT_PROMPT_CHARS_DEVICE
        }

    val agentMaxOutputTokens: Int
        get() = if (isCompactEmulatorProfile()) {
            AGENT_MAX_OUTPUT_TOKENS_EMULATOR
        } else {
            AGENT_MAX_OUTPUT_TOKENS
        }

    /** Loads the LiteRT-LM engine. No token-generation prime is performed. */
    suspend fun warmUp(): Boolean = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            if (!DeviceCapabilityChecker.isAiCapable(context)) return@withLock false
            val status = modelManager.modelStatus.value
            if (status is LlamaModelManager.ModelStatus.Unsupported) return@withLock false
            val modelPath = modelManager.getModelPath() ?: return@withLock false
            ensureEngineLoaded(modelPath)
            true
        }
    }

    fun close() {
        val engine = synchronized(engineStateLock) {
            val current = liteRtEngine
            liteRtEngine = null
            activeBackend = null
            current
        }
        if (engine?.isInitialized() == true) {
            runCatching { engine.close() }
                .onFailure { Log.w(TAG, "LiteRT-LM engine close failed", it) }
        }
    }

    private suspend fun infer(
        mode: Mode,
        primaryText: String,
        secondaryText: String? = null,
        taskId: String,
        maxTokensOverride: Int? = null,
    ): String = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            if (!DeviceCapabilityChecker.isAiCapable(context)) {
                return@withLock fallback(
                    mode,
                    primaryText,
                    "This device does not meet the minimum 4 GB RAM requirement for the on-device AI model.",
                )
            }

            val status = modelManager.modelStatus.value
            if (status is LlamaModelManager.ModelStatus.Unsupported) {
                _progress.value = InferenceProgress.Error(taskId, status.message)
                throw IllegalStateException(status.message)
            }

            val modelPath = modelManager.getModelPath()
                ?: return@withLock fallback(mode, primaryText)
            checkThermalThrottle()

            try {
                ensureEngineLoaded(modelPath)
                val modeMaxTokens = when (mode) {
                    Mode.SUMMARISE -> MAX_TOKENS_SUMMARISE
                    Mode.REWRITE -> MAX_TOKENS_REWRITE
                    Mode.QUESTION -> MAX_TOKENS_QUESTION
                }
                val thermalMaxTokens = if (isThermallyThrottled()) {
                    maxOf(MIN_MAX_TOKENS, modeMaxTokens / 2)
                } else {
                    modeMaxTokens
                }
                val maxTokens = maxTokensOverride?.coerceIn(MIN_MAX_TOKENS, thermalMaxTokens)
                    ?: thermalMaxTokens
                generate(
                    messages = buildMessages(mode, primaryText, secondaryText),
                    taskId = taskId,
                    maxTokens = maxTokens,
                    temperature = when (mode) {
                        Mode.SUMMARISE -> 0.1f
                        Mode.REWRITE -> 0.2f
                        Mode.QUESTION -> 0.2f
                    },
                    topP = 0.9f,
                    timeoutMs = timeoutMsFor(mode),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "LiteRT-LM inference failed mode=$mode taskId=$taskId", failure)
                _progress.value = InferenceProgress.Error(
                    taskId,
                    failure.message ?: "On-device inference failed",
                )
                fallback(
                    mode,
                    primaryText,
                    "The on-device model failed to run. Restart the app or re-download it in Settings.",
                )
            }
        }
    }

    /**
     * Initializes the supported LiteRT-LM engine. ARM64 tries GPU first and
     * immediately falls back to CPU if the device OpenCL stack rejects it.
     */
    private fun ensureEngineLoaded(modelPath: String) {
        synchronized(engineStateLock) {
            if (liteRtEngine?.isInitialized() == true) return

            val cache = File(context.cacheDir, "litertlm").also { it.mkdirs() }
            val cpuThreads = (Runtime.getRuntime().availableProcessors() - 1)
                .coerceIn(1, MAX_CPU_THREADS)
            val backends = if (isCompactEmulatorProfile()) {
                listOf<Backend>(Backend.CPU(threadCount = cpuThreads))
            } else {
                listOf<Backend>(Backend.GPU(), Backend.CPU(threadCount = cpuThreads))
            }
            val failures = mutableListOf<Throwable>()

            for (backend in backends) {
                val startedAt = SystemClock.elapsedRealtime()
                val candidate = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        maxNumTokens = MODEL_CONTEXT_TOKENS,
                        cacheDir = cache.absolutePath,
                    ),
                )
                try {
                    candidate.initialize()
                    liteRtEngine = candidate
                    activeBackend = backend.name
                    Log.i(
                        TAG,
                        "LiteRT-LM initialized backend=${backend.name} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                    )
                    return
                } catch (failure: Throwable) {
                    failures += failure
                    Log.w(TAG, "LiteRT-LM ${backend.name} initialization failed", failure)
                    if (candidate.isInitialized()) runCatching { candidate.close() }
                }
            }

            val details = failures.joinToString("; ") { it.message ?: it::class.java.simpleName }
            throw ModelUnavailableException("LiteRT-LM could not initialize: $details")
        }
    }

    private suspend fun generate(
        messages: List<ChatMessage>,
        taskId: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        timeoutMs: Long,
        onToken: (String) -> Unit = {},
    ): String {
        val prepared = prepareConversation(messages)
        val boundedMaxTokens = maxTokens.coerceIn(MIN_MAX_TOKENS, agentMaxOutputTokens)
        val engine = synchronized(engineStateLock) {
            liteRtEngine?.takeIf { it.isInitialized() }
        } ?: error("LiteRT-LM engine is not initialized")
        val conversationConfig = ConversationConfig(
            systemInstruction = buildString {
                append(prepared.systemInstruction)
                if (isNotEmpty()) appendLine()
                append("Keep the final answer within $boundedMaxTokens tokens.")
            }.let(Contents::of),
            initialMessages = prepared.history,
            samplerConfig = SamplerConfig(
                topK = DEFAULT_TOP_K,
                topP = topP.coerceIn(0.1f, 1f).toDouble(),
                temperature = temperature.coerceIn(0f, 1f).toDouble(),
            ),
            channels = emptyList(),
        )

        val result = StringBuilder()
        val firstTokenMs = AtomicLong(-1L)
        val startedAt = SystemClock.elapsedRealtime()
        _progress.value = InferenceProgress.Thinking("", taskId)

        try {
            engine.createConversation(conversationConfig).use { conversation ->
                withTimeout(timeoutMs) {
                    conversation.streamMessages(prepared.currentUserMessage).collect { message ->
                        val token = message.toString()
                        if (token.isNotEmpty()) {
                            firstTokenMs.compareAndSet(
                                -1L,
                                SystemClock.elapsedRealtime() - startedAt,
                            )
                            result.append(token)
                            onToken(token)
                            _progress.value = InferenceProgress.Thinking(result.toString(), taskId)
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            val partial = cleanModelOutput(result.toString())
            if (partial.isNotBlank()) {
                _progress.value = InferenceProgress.Done(taskId, partial)
                return partial
            }
            throw ModelUnavailableException(
                "LiteRT-LM produced no token within ${timeoutMs / 1_000} seconds.",
            )
        }

        val completed = cleanModelOutput(result.toString())
        if (completed.isBlank()) error("The on-device model returned an empty response.")
        Log.i(
            TAG,
            "LiteRT-LM turn complete taskId=$taskId backend=$activeBackend " +
                "chars=${completed.length} firstTokenMs=${firstTokenMs.get()} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
        _progress.value = InferenceProgress.Done(taskId, completed)
        return completed
    }

    private fun cleanModelOutput(raw: String): String =
        raw.replace(THINKING_BLOCK_REGEX, "").trim()

    /**
     * LiteRT-LM 0.14.0's precompiled Flow overload has a known coroutine ABI
     * mismatch in its completion callback. Wrapping the supported callback
     * overload locally preserves streaming while linking channel completion to
     * the exact coroutine runtime packaged by this app.
     */
    private fun Conversation.streamMessages(text: String): Flow<Message> = callbackFlow {
        val finished = AtomicBoolean(false)
        sendMessageAsync(
            text,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    trySend(message)
                }

                override fun onDone() {
                    finished.set(true)
                    channel.close(null)
                }

                override fun onError(throwable: Throwable) {
                    finished.set(true)
                    channel.close(throwable)
                }
            },
            emptyMap(),
        )
        awaitClose {
            if (finished.compareAndSet(false, true) && this@streamMessages.isAlive) {
                this@streamMessages.cancelProcess()
            }
        }
    }

    private data class PreparedConversation(
        val systemInstruction: String,
        val history: List<Message>,
        val currentUserMessage: String,
    )

    private fun prepareConversation(messages: List<ChatMessage>): PreparedConversation {
        val meaningful = messages.filter { it.content.isNotBlank() }
        require(meaningful.isNotEmpty()) { "Agent prompt cannot be empty" }
        val currentUserIndex = meaningful.indexOfLast { it.role.equals("user", ignoreCase = true) }
        require(currentUserIndex >= 0) { "Agent prompt has no user message" }

        val system = meaningful
            .filter { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n\n") { it.content }
        val history = meaningful.take(currentUserIndex).mapNotNull { message ->
            when {
                message.role.equals("user", ignoreCase = true) -> Message.user(message.content)
                message.role.equals("assistant", ignoreCase = true) ||
                    message.role.equals("model", ignoreCase = true) -> Message.model(message.content)
                else -> null
            }
        }
        return PreparedConversation(
            systemInstruction = system,
            history = history,
            // Qwen3's documented switch skips its hidden reasoning phase. This
            // keeps interactive note/chat turns direct and materially lowers
            // time-to-first-answer without substituting any canned response.
            currentUserMessage = meaningful[currentUserIndex].content + "\n/no_think",
        )
    }

    private fun buildMessages(
        mode: Mode,
        primary: String,
        secondary: String?,
    ): List<ChatMessage> {
        val system = when (mode) {
            Mode.SUMMARISE ->
                "You are a concise note-taking assistant. Summarise the note in at most 3 short " +
                    "lines. Output only the summary."
            Mode.REWRITE ->
                "Rewrite the note in clear professional Markdown. Preserve every fact and detail. " +
                    "Output only the rewritten note."
            Mode.QUESTION -> if (secondary != null) {
                "Answer the user's question from the supplied context. Be concise and do not invent details."
            } else {
                "Answer the user's question concisely and accurately."
            }
        }
        val contextLimit = contextCharLimitFor(mode)
        val user = if (mode == Mode.QUESTION && secondary != null) {
            "Context:\n${secondary.take(contextLimit)}\n\nQuestion: ${primary.take(MAX_QUESTION_CHARS)}"
        } else {
            primary.take(contextLimit)
        }
        return listOf(ChatMessage("system", system), ChatMessage("user", user))
    }

    private fun fallback(
        mode: Mode,
        text: String,
        questionMessage: String =
            "On-device AI model not downloaded (${LlamaModelManager.MODEL_SIZE_LABEL}). " +
                "Download it in Settings to enable question answering.",
    ): String = when (mode) {
        Mode.SUMMARISE -> Summarizer.lightweightPreview(text)
        Mode.REWRITE -> text.trim()
        Mode.QUESTION -> questionMessage
    }

    @Volatile
    private var thermallyThrottled = false

    private fun isThermallyThrottled(): Boolean = thermallyThrottled

    private fun checkThermalThrottle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        try {
            val throttled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.getThermalHeadroom(1) < THERMAL_HEADROOM_THROTTLE_THRESHOLD
            } else {
                @Suppress("DEPRECATION")
                manager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            }
            thermallyThrottled = throttled
            if (throttled) _progress.value = InferenceProgress.Throttled
        } catch (failure: Exception) {
            Log.w(TAG, "Thermal check failed", failure)
        }
    }

    private fun isCompactEmulatorProfile(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "x86_64" || it == "x86" }

    private fun contextCharLimitFor(mode: Mode): Int = when (mode) {
        Mode.SUMMARISE -> MAX_CONTEXT_CHARS_SUMMARISE
        Mode.REWRITE -> MAX_CONTEXT_CHARS_REWRITE
        Mode.QUESTION -> QUESTION_CONTEXT_CHAR_LIMIT
    }

    private fun timeoutMsFor(mode: Mode): Long = when (mode) {
        Mode.SUMMARISE -> SUMMARISE_GENERATION_TIMEOUT_MS
        Mode.REWRITE, Mode.QUESTION -> INTERACTIVE_GENERATION_TIMEOUT_MS
    }

    private fun newTaskId(): String = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "LlamaEngine"
        private const val MIN_MAX_TOKENS = 1
        private const val MAX_TOKENS_SUMMARISE = 64
        private const val MAX_TOKENS_REWRITE = 160
        private const val MAX_TOKENS_QUESTION = 128
        private const val AGENT_MAX_OUTPUT_TOKENS = 256
        private const val AGENT_MAX_OUTPUT_TOKENS_EMULATOR = 192
        private const val AGENT_PROMPT_CHARS_DEVICE = 4_000
        private const val AGENT_PROMPT_CHARS_EMULATOR = 2_200
        private const val MAX_CONTEXT_CHARS_SUMMARISE = 900
        private const val MAX_CONTEXT_CHARS_REWRITE = 2_000
        internal const val QUESTION_CONTEXT_CHAR_LIMIT = 1_000
        private const val MAX_QUESTION_CHARS = 500
        private const val MODEL_CONTEXT_TOKENS = 2_048
        private const val DEFAULT_TOP_K = 40
        private const val MAX_CPU_THREADS = 4
        private const val SUMMARISE_GENERATION_TIMEOUT_MS = 20_000L
        private const val INTERACTIVE_GENERATION_TIMEOUT_MS = 30_000L
        private const val AGENT_GENERATION_TIMEOUT_MS = 35_000L
        private const val THERMAL_HEADROOM_THROTTLE_THRESHOLD = 0.15f
        private val THINKING_BLOCK_REGEX = Regex(
            pattern = "<think>.*?</think>\\s*",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
