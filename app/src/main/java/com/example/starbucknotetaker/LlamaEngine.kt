package com.example.starbucknotetaker

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
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

/**
 * On-device LLM inference engine backed by MLC LLM ([MLCEngine]) running
 * Llama 3.2 3B Instruct (q4f16_0 quantisation).
 *
 * **Model library (`.tar` flow):**
 * The compiled model library is distributed as a `.tar` asset bundled inside the APK
 * (`assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar`).  The archive contains
 * the MLC system-library object files that the TVM runtime links at runtime.
 * On the first inference attempt [LlamaModelManager.extractModelLibIfNeeded] extracts
 * the archive to `filesDir/lib/Llama-3.2-3B-Instruct-q4f16_0-MLC-android/`.  The
 * resulting absolute directory path is then passed to [MLCEngine.reload] as `modelLib`
 * instead of a JNI library name.
 *
 * The model weights (~2 GB) are not bundled in the APK; they are downloaded
 * to `filesDir/models/Llama-3.2-3B-Instruct-q4f16_0-MLC/` via [LlamaModelManager].
 * Until the weights are present, every inference call falls back to the
 * rule-based heuristics in [Summarizer].
 *
 * Devices with less than [DeviceCapabilityChecker.MIN_RAM_BYTES] (4 GB) total RAM are
 * considered incapable and will always receive the rule-based fallback without
 * attempting to load the model.
 *
 * Supported modes:
 *  - [Mode.SUMMARISE] — concise 1–3 line note preview
 *  - [Mode.REWRITE]   — rewritten in a clean, professional style
 *  - [Mode.QUESTION]  — answer a free-form question using optional context
 *
 * Thermal throttling:
 *  On API 31+ [PowerManager.thermalHeadroom] is polled before each call.
 *  When headroom is critically low, the request's `maxTokens` is halved and
 *  [InferenceProgress.Throttled] is emitted so the UI can surface a warning.
 */
class LlamaEngine(private val context: Context) {

    // ------------------------------------------------------------------
    // Public types
    // ------------------------------------------------------------------

    enum class Mode { SUMMARISE, REWRITE, QUESTION }

    sealed class InferenceProgress {
        object Idle : InferenceProgress()
        /** Partial token output streamed from the model as it generates. */
        data class Thinking(val partialText: String, val taskId: String) : InferenceProgress()
        /** Device is thermally constrained — inference continues at reduced capacity. */
        object Throttled : InferenceProgress()
        /** Inference finished successfully. */
        data class Done(val taskId: String, val result: String) : InferenceProgress()
        /** Inference failed. */
        data class Error(val taskId: String, val message: String) : InferenceProgress()
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private val _progress = MutableStateFlow<InferenceProgress>(InferenceProgress.Idle)
    val progress: StateFlow<InferenceProgress> = _progress

    private val inferenceMutex = Mutex()
    private val mlcEngine: MLCEngine by lazy { MLCEngine() }
    private val modelManager = LlamaModelManager(context)
    val modelStatus: StateFlow<LlamaModelManager.ModelStatus> = modelManager.modelStatus

    @Volatile
    private var engineLoaded = false

    /**
     * `null`  = not yet tested
     * `true`  = library loaded successfully at least once
     * `false` = library failed to load; all subsequent inference calls skip the model
     */
    @Volatile
    private var nativeLibAvailable: Boolean? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Summarises [text] in 1–3 concise lines.
     * Falls back to rule-based heuristics when the model is unavailable.
     */
    suspend fun summarise(text: String, taskId: String = newTaskId()): String =
        infer(Mode.SUMMARISE, text, taskId = taskId)

    /**
     * Rewrites [text] in a cleaner, more professional style.
     * Falls back to returning the original text when the model is unavailable.
     */
    suspend fun rewrite(text: String, taskId: String = newTaskId()): String =
        infer(Mode.REWRITE, text, taskId = taskId)

    /**
     * Answers [question] using [context] as optional grounding material.
     * Falls back to a descriptive message when the model is unavailable.
     */
    suspend fun answer(
        question: String,
        context: String? = null,
        taskId: String = newTaskId(),
    ): String = infer(Mode.QUESTION, question, context, taskId)

    /** Unloads the model from memory and releases engine resources. */
    fun close() {
        if (engineLoaded) {
            runCatching { mlcEngine.unload() }
            engineLoaded = false
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
            // Skip the model entirely on devices that do not meet the RAM threshold
            if (!DeviceCapabilityChecker.isAiCapable(context)) {
                return@withLock fallback(
                    mode, primaryText,
                    questionMessage =
                        "This device does not meet the minimum 4 GB RAM requirement " +
                        "to run the on-device AI model.",
                )
            }

            val modelPath = modelManager.getModelPath()
            if (modelPath == null) {
                // Model weights not yet downloaded — use rule-based fallback
                return@withLock fallback(mode, primaryText)
            }

            checkThermalThrottle()

            runCatching {
                ensureEngineLoaded(modelPath)
                generateWithMlc(mode, primaryText, secondaryText, taskId)
            }.getOrElse { e ->
                val diagInfo = modelManager.debugModelDirInfo()
                Log.e(TAG, "MLC inference failed for mode=$mode taskId=$taskId " +
                    "[${e::class.simpleName}]: ${e.message}\n$diagInfo", e)
                _progress.value = InferenceProgress.Error(taskId, e.message ?: "inference error")
                // Native-library failures (missing .so or JVM class-init error from a previous
                // failed load attempt) are permanent for this process lifetime.  Cache the state
                // so future calls skip the engine immediately, and rethrow as RuntimeException so
                // LlamaForegroundService broadcasts isError=true instead of silently storing an
                // error message as if it were a real AI answer.
                if (e is UnsatisfiedLinkError || e is NoClassDefFoundError) {
                    nativeLibAvailable = false
                    engineLoaded = false
                    throw RuntimeException(
                        "The on-device AI feature is unavailable on this device or build. " +
                        "Detail: ${e.message}",
                        e,
                    )
                }
                // Return a distinct message for QUESTION mode so the user is not told the model
                // is not downloaded when it is actually present but failed to load or run.
                fallback(
                    mode, primaryText,
                    questionMessage =
                        "AI model failed to run (${e::class.simpleName}). " +
                        "Try restarting the app, or delete and " +
                        "re-download the model in Settings → AI model.",
                )
            }
        }
    }

    private fun ensureEngineLoaded(modelPath: String) {
        // Short-circuit immediately if a previous attempt confirmed the native library is absent.
        // Without this guard the JVM would throw NoClassDefFoundError on every subsequent call
        // (because a class-init failed earlier), which is harder to diagnose.
        if (nativeLibAvailable == false) {
            throw UnsatisfiedLinkError(
                "The on-device AI feature is unavailable on this device or build."
            )
        }
        if (engineLoaded) return

        // The compiled model kernel library is built by the Gradle task `buildModelLibSo` and
        // packaged in the APK's jniLibs.  Android extracts it to nativeLibraryDir at install time.
        val modelSoPath = "${context.applicationInfo.nativeLibraryDir}/${LlamaModelManager.MODEL_SO_FILENAME}"
        val modelSoFile = java.io.File(modelSoPath)
        if (!modelSoFile.exists()) {
            val diagInfo = modelManager.debugModelDirInfo()
            Log.e(TAG, "Model library .so not found: $modelSoPath\n$diagInfo")
            throw IllegalStateException(
                "Model library not found at $modelSoPath. " +
                "The APK may need to be rebuilt so that the Gradle task " +
                "'buildModelLibSo' can compile the model kernel library."
            )
        }

        Log.i(TAG, "Loading MLCEngine: lib=$modelSoPath path=$modelPath")
        Log.d(TAG, modelManager.debugModelDirInfo())
        try {
            mlcEngine.reload(modelSoPath, modelPath)
        } catch (e: Throwable) {
            val diagInfo = modelManager.debugModelDirInfo()
            Log.e(TAG, "MLCEngine.reload failed [${e::class.qualifiedName}]: ${e.message}\n$diagInfo", e)
            throw e
        }
        engineLoaded = true
        nativeLibAvailable = true
        Log.i(TAG, "MLCEngine loaded successfully")
    }

    private fun generateWithMlc(
        mode: Mode,
        primaryText: String,
        secondaryText: String?,
        taskId: String,
    ): String {
        val messages = buildMessages(mode, primaryText, secondaryText)
        val maxTokens = if (isThermallyThrottled()) MAX_TOKENS_THROTTLED else MAX_TOKENS_NORMAL
        val request = OpenAIProtocol.ChatCompletionRequest(
            messages = messages,
            model = LlamaModelManager.MODEL_DISPLAY_NAME,
            stream = true,
            maxTokens = maxTokens,
        )

        val sb = StringBuilder()
        _progress.value = InferenceProgress.Thinking("", taskId)

        mlcEngine.chat.completions.create(request) { response ->
            response.choices.firstOrNull()?.delta?.content?.let { token ->
                sb.append(token)
                _progress.value = InferenceProgress.Thinking(sb.toString(), taskId)
            }
        }

        val result = sb.toString().trim()
        _progress.value = InferenceProgress.Done(taskId, result)
        return result
    }

    /**
     * Returns a rule-based fallback result for [mode].
     *
     * [questionMessage] overrides the default QUESTION fallback so callers can provide
     * an accurate message for each failure scenario (model missing vs. engine error vs.
     * device capability) without duplicating the SUMMARISE / REWRITE logic.
     */
    private fun fallback(
        mode: Mode,
        text: String,
        questionMessage: String =
            "Llama 3.2 3B not yet downloaded (~2 GB). " +
            "Download it in Settings → AI model to enable question answering.",
    ): String = when (mode) {
        Mode.SUMMARISE -> Summarizer.lightweightPreview(text)
        Mode.REWRITE   -> text.trim()
        Mode.QUESTION  -> questionMessage
    }

    // ------------------------------------------------------------------
    // Message construction for the Llama 3.2 Instruct workflow
    // ------------------------------------------------------------------

    private fun buildMessages(
        mode: Mode,
        primary: String,
        secondary: String?,
    ): List<OpenAIProtocol.ChatCompletionMessage> {
        val system = when (mode) {
            Mode.SUMMARISE ->
                "You are a concise note-taking assistant. " +
                "Summarise the following note in at most 3 short, clear lines. " +
                "Output only the summary — no preamble, no labels."
            Mode.REWRITE ->
                "You are a professional writing assistant. " +
                "Rewrite the following note in a cleaner, more professional style. " +
                "Preserve every fact and detail. Output only the rewritten note."
            Mode.QUESTION ->
                if (secondary != null) {
                    "You are a helpful assistant. " +
                    "Answer the user's question using the provided context. Be concise and direct."
                } else {
                    "You are a helpful assistant. Answer the question concisely and accurately."
                }
        }

        val userContent = when {
            mode == Mode.QUESTION && secondary != null ->
                "Context:\n${secondary.take(MAX_CONTEXT_CHARS)}\n\nQuestion: ${primary.take(MAX_QUESTION_CHARS)}"
            else -> primary.take(MAX_CONTEXT_CHARS)
        }

        return listOf(
            OpenAIProtocol.ChatCompletionMessage(role = "system", content = system),
            OpenAIProtocol.ChatCompletionMessage(role = "user",   content = userContent),
        )
    }

    // ------------------------------------------------------------------
    // Thermal throttle
    // ------------------------------------------------------------------

    @Volatile
    private var thermallyThrottled = false

    private fun isThermallyThrottled() = thermallyThrottled

    private fun checkThermalThrottle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        try {
            val throttled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pm.getThermalHeadroom(1) < THERMAL_HEADROOM_THROTTLE_THRESHOLD
            } else {
                @Suppress("DEPRECATION")
                pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            }
            if (throttled != thermallyThrottled) {
                thermallyThrottled = throttled
                if (throttled) {
                    Log.w(TAG, "Thermal throttle active — reducing max tokens")
                    _progress.value = InferenceProgress.Throttled
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
        private const val MAX_TOKENS_NORMAL    = 512
        private const val MAX_TOKENS_THROTTLED = 256
        private const val MAX_CONTEXT_CHARS    = 3000
        private const val MAX_QUESTION_CHARS   = 500
        private const val THERMAL_HEADROOM_THROTTLE_THRESHOLD = 0.15f
    }
}
