package com.example.starbucknotetaker

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.tvm.Base
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device LLM inference engine backed by MLC LLM ([MLCEngine]) running
 * Llama 3.2 Instruct. ARM64 devices run the full 3B q4f16 OpenCL profile so
 * modern mobile GPUs can accelerate inference. x86_64 emulators run a compact
 * 1B q4f32 AVX2 CPU profile because web/CI emulators cannot assume access to a
 * discrete host GPU and gfxstream capabilities vary by host.
 *
 * **Model library (system-lib `.so` flow):**
 * The compiled model library is distributed as a `.tar` asset bundled inside the APK
 * (`assets/Llama-3.2-3B-Instruct-*-android*.tar`).  The Gradle task
 * `buildModelLibSo` links the ABI-specific `.o` files from that archive into a
 * matching shared library placed in the
 * APK's `jniLibs/`.
 * Android extracts it to `nativeLibraryDir` at install time.
 *
 * On the first inference attempt [ensureEngineLoaded] loads the library via
 * `System.load(path)`.  This registers the model's TVM FFI system-library metadata
 * with the packed TVM runtime.  [MLCEngine.reload] is then called with
 * the ABI-specific `system://` handle, which
 * instructs MLC-LLM to retrieve the pre-registered module via `ffi.SystemLib`
 * rather than attempting to load a `.so` file through TVM's file-loader.
 *
 * The model weights (~2 GB) are not bundled in the APK; [LlamaModelManager]
 * downloads the matching ABI-specific weight profile under `filesDir/models/`.
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
    private val modelManager = LlamaModelManager(context)
    private val mlcEngine: MLCEngine by lazy {
        MLCEngine(modelManager.getRuntimeMlcDeviceType() ?: "opencl")
    }
    val modelStatus: StateFlow<LlamaModelManager.ModelStatus> = modelManager.modelStatus

    @Volatile
    private var engineLoaded = false

    @Volatile
    private var enginePrimed = false

    val isWarm: Boolean
        get() = engineLoaded && enginePrimed

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
    suspend fun summarise(
        text: String,
        taskId: String = newTaskId(),
        maxTokensOverride: Int? = null,
    ): String =
        infer(Mode.SUMMARISE, text, taskId = taskId, maxTokensOverride = maxTokensOverride)

    /**
     * Rewrites [text] in a cleaner, more professional style.
     * Falls back to returning the original text when the model is unavailable.
     */
    suspend fun rewrite(
        text: String,
        taskId: String = newTaskId(),
        maxTokensOverride: Int? = null,
    ): String =
        infer(Mode.REWRITE, text, taskId = taskId, maxTokensOverride = maxTokensOverride)

    /**
     * Answers [question] using [context] as optional grounding material.
     * Falls back to a descriptive message when the model is unavailable.
     */
    suspend fun answer(
        question: String,
        context: String? = null,
        taskId: String = newTaskId(),
        maxTokensOverride: Int? = null,
    ): String = infer(Mode.QUESTION, question, context, taskId, maxTokensOverride)

    /**
     * Loads the native engine and model weights without generating text.
     * Returns false when the current device/build/model state requires fallback.
     */
    suspend fun warmUp(): Boolean = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            if (!DeviceCapabilityChecker.isAiCapable(context)) return@withLock false
            val currentStatus = modelManager.modelStatus.value
            if (currentStatus is LlamaModelManager.ModelStatus.Unsupported) return@withLock false
            val modelPath = modelManager.getModelPath() ?: return@withLock false
            ensureEngineLoaded(modelPath)
            primeEngine()
            true
        }
    }

    /** Unloads the model from memory and releases engine resources. */
    fun close() {
        if (engineLoaded) {
            runCatching { mlcEngine.unload() }
            engineLoaded = false
            enginePrimed = false
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
        maxTokensOverride: Int? = null,
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

            val currentStatus = modelManager.modelStatus.value
            if (currentStatus is LlamaModelManager.ModelStatus.Unsupported) {
                _progress.value = InferenceProgress.Error(taskId, currentStatus.message)
                throw IllegalStateException(currentStatus.message)
            }

            val modelPath = modelManager.getModelPath()
            if (modelPath == null) {
                // Model weights not yet downloaded — use rule-based fallback
                return@withLock fallback(mode, primaryText)
            }

            checkThermalThrottle()

            runCatching {
                ensureEngineLoaded(modelPath)
                generateWithMlc(mode, primaryText, secondaryText, taskId, maxTokensOverride)
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                if (e is InferenceTimeoutException) {
                    _progress.value = InferenceProgress.Error(taskId, e.message ?: "inference timeout")
                    return@withLock fallback(
                        mode,
                        primaryText,
                        questionMessage =
                            "The on-device 3B model did not produce output within " +
                            "${e.timeoutSeconds} seconds. Try a shorter request or retry.",
                    )
                }
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
        val modelSoPath =
            "${context.applicationInfo.nativeLibraryDir}/${modelManager.getRuntimeModelSoFilename()}"
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
            configureCpuThreadingIfNeeded()
            // Load and initialise TVM before the model library. If Android only loads the
            // packed runtime as a DT_NEEDED dependency of the model .so, TVM's JNI entry
            // points are not registered for org.apache.tvm.LibInfo.
            Base.ensureInitialized()
            // Load the model kernel library via Android's native linker. This registers the
            // model's TVM FFI system-library metadata with the packed TVM runtime.
            // Must happen before reload() so ffi.SystemLib can find the model module.
            System.load(modelSoPath)
            // Pass the system:// handle so MLC-LLM retrieves the pre-registered module via
            // TVM's system-lib mechanism instead of trying to load the .so through TVM's
            // file-loader.
            mlcEngine.reload(
                modelManager.getRuntimeModelLibSystemHandle(),
                modelPath,
                mode = modelManager.getRuntimeMlcEngineMode() ?: "interactive",
                runtimeConfig = runtimeConfigForDevice(),
            )
        } catch (e: Throwable) {
            val diagInfo = modelManager.debugModelDirInfo()
            Log.e(TAG, "MLCEngine.reload failed [${e::class.qualifiedName}]: ${e.message}\n$diagInfo", e)
            throw e
        }
        engineLoaded = true
        nativeLibAvailable = true
        Log.i(TAG, "MLCEngine loaded successfully")
    }

    /**
     * Runs one token through the fully loaded engine while the app is starting.
     * This pays first-request kernel and KV-cache setup before an interactive
     * request reaches the engine.
     */
    private fun primeEngine() {
        if (enginePrimed) return
        val startedAt = SystemClock.elapsedRealtime()
        val request = OpenAIProtocol.ChatCompletionRequest(
            messages = listOf(
                // A single minimal token is enough to initialise the execution
                // kernels and KV cache. Keeping this prompt tiny matters on CPU
                // devices because prefill cost grows with every prompt token.
                OpenAIProtocol.ChatCompletionMessage(role = "user", content = "."),
            ),
            model = LlamaModelManager.MODEL_DISPLAY_NAME,
            stream = true,
            maxTokens = 1,
            temperature = 0.0f,
            topP = 1.0f,
        )
        mlcEngine.chat.completions.create(request, timeoutSeconds = WARMUP_TIMEOUT_SECONDS) { }
        mlcEngine.reset()
        enginePrimed = true
        Log.i(TAG, "MLCEngine primed in ${SystemClock.elapsedRealtime() - startedAt}ms")
    }

    private fun configureCpuThreadingIfNeeded() {
        if (modelManager.getRuntimeMlcDeviceType() != "cpu") return
        // Reserve one CPU for UI, lookup, and Android system work while still
        // using the rest for model loading/generation. ARM production builds use
        // OpenCL and are not constrained by this emulator/CPU profile.
        val availableProcessors = Runtime.getRuntime().availableProcessors()
        val requestedThreads = (availableProcessors - 1)
            .coerceIn(1, MAX_CPU_THREADS)
        runCatching {
            Os.setenv("TVM_NUM_THREADS", requestedThreads.toString(), true)
            Os.setenv("OMP_NUM_THREADS", requestedThreads.toString(), true)
            Os.setenv("TVM_EXCLUDE_WORKER0", "0", true)
        }.onSuccess {
            Log.i(TAG, "Configured TVM CPU threading: requestedThreads=$requestedThreads")
        }.onFailure {
            Log.w(TAG, "Failed to configure TVM CPU threading", it)
        }
    }

    /**
     * Sizes MLC for the device instead of accepting its interactive preset,
     * which reserves buffers for Llama's complete 128K context window. The
     * latter is wasteful for note tasks and cannot fit beside 3B weights on a
     * typical mobile GPU. Modern ARM phones receive a larger context and
     * prefill batch, while the emulator uses a compact single-user CPU profile.
     */
    private fun runtimeConfigForDevice(): MLCEngine.RuntimeConfig {
        val totalRamBytes = runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as ActivityManager
            ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo).totalMem
        }.getOrDefault(0L)
        val totalRamGb = totalRamBytes.toDouble() / BYTES_PER_GIB
        val isEmulatorProfile = modelManager.getRuntimeMlcDeviceType() == "cpu" &&
            Build.SUPPORTED_ABIS.any { it == "x86_64" || it == "x86" }

        val config = when {
            isEmulatorProfile -> MLCEngine.RuntimeConfig(
                // CPU inference uses system RAM; this value only bounds MLC's
                // capacity estimator and does not claim a discrete GPU.
                gpuMemoryUtilization = 0.80,
                maxNumSequence = 1,
                maxTotalSequenceLength = 1_024,
                maxSingleSequenceLength = 1_024,
                prefillChunkSize = 32,
            )
            totalRamGb >= 12.0 -> MLCEngine.RuntimeConfig(
                gpuMemoryUtilization = 0.90,
                maxNumSequence = 1,
                maxTotalSequenceLength = 8_192,
                maxSingleSequenceLength = 8_192,
                prefillChunkSize = 512,
            )
            totalRamGb >= 8.0 -> MLCEngine.RuntimeConfig(
                gpuMemoryUtilization = 0.90,
                maxNumSequence = 1,
                maxTotalSequenceLength = 4_096,
                maxSingleSequenceLength = 4_096,
                prefillChunkSize = 256,
            )
            totalRamGb >= 6.0 -> MLCEngine.RuntimeConfig(
                gpuMemoryUtilization = 0.90,
                maxNumSequence = 1,
                maxTotalSequenceLength = 3_072,
                maxSingleSequenceLength = 3_072,
                prefillChunkSize = 192,
            )
            else -> MLCEngine.RuntimeConfig(
                gpuMemoryUtilization = 0.92,
                maxNumSequence = 1,
                maxTotalSequenceLength = 2_048,
                maxSingleSequenceLength = 2_048,
                prefillChunkSize = 128,
            )
        }
        Log.i(
            TAG,
            "MLC hardware profile: device=${modelManager.getRuntimeMlcDeviceType()} " +
                "ram=${"%.1f".format(totalRamGb)}GiB cores=${Runtime.getRuntime().availableProcessors()} " +
                "config=$config",
        )
        return config
    }

    private fun generateWithMlc(
        mode: Mode,
        primaryText: String,
        secondaryText: String?,
        taskId: String,
        maxTokensOverride: Int?,
    ): String {
        val messages = buildMessages(mode, primaryText, secondaryText)
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
        val request = OpenAIProtocol.ChatCompletionRequest(
            messages = messages,
            model = LlamaModelManager.MODEL_DISPLAY_NAME,
            stream = true,
            maxTokens = maxTokens,
            temperature = when (mode) {
                Mode.SUMMARISE -> 0.1f
                Mode.REWRITE -> 0.2f
                Mode.QUESTION -> 0.2f
            },
            topP = 0.9f,
        )

        val sb = StringBuilder()
        val firstTokenMs = AtomicLong(-1L)
        val timeoutSeconds = timeoutSecondsFor(mode)
        val startedAt = SystemClock.elapsedRealtime()
        _progress.value = InferenceProgress.Thinking("", taskId)

        try {
            mlcEngine.reset()
            mlcEngine.chat.completions.create(request, timeoutSeconds = timeoutSeconds) { response ->
                response.choices.firstOrNull()?.delta?.content?.let { token ->
                    if (token.isNotEmpty()) {
                        firstTokenMs.compareAndSet(-1L, SystemClock.elapsedRealtime() - startedAt)
                    }
                    sb.append(token)
                    _progress.value = InferenceProgress.Thinking(sb.toString(), taskId)
                }
            }
        } catch (e: TimeoutException) {
            val partial = sb.toString().trim()
            if (partial.isNotBlank()) {
                val result = partialResultForTimeout(mode, partial, timeoutSeconds)
                _progress.value = InferenceProgress.Done(taskId, result)
                return result
            }
            throw InferenceTimeoutException(mode, timeoutSeconds, e)
        }

        val result = sb.toString().trim()
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        Log.i(
            TAG,
            "MLC inference complete mode=$mode taskId=$taskId chars=${result.length} " +
                "maxTokens=$maxTokens firstTokenMs=${firstTokenMs.get()} " +
                "elapsedMs=$elapsedMs timeoutSecs=$timeoutSeconds",
        )
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
                "Preserve every fact and detail. Use clean Markdown with short headings, " +
                "paragraphs, and bullet or numbered lists where they improve readability. " +
                "Do not use a code fence. Output only the rewritten note."
            Mode.QUESTION ->
                if (secondary != null) {
                    "You are a helpful assistant. " +
                    "Answer the user's question using the provided context. Be concise and direct. " +
                    "If web lookup sources are included, use them only when relevant and cite URLs."
                } else {
                    "You are a helpful assistant. Answer the question concisely and accurately."
                }
        }

        val contextLimit = contextCharLimitFor(mode)
        val userContent = when {
            mode == Mode.QUESTION && secondary != null ->
                "Context:\n${secondary.take(contextLimit)}\n\nQuestion: ${primary.take(MAX_QUESTION_CHARS)}"
            else -> primary.take(contextLimit)
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

    private fun timeoutSecondsFor(mode: Mode): Long = when (mode) {
        Mode.SUMMARISE -> TIMEOUT_SUMMARISE_SECONDS
        Mode.REWRITE -> TIMEOUT_INTERACTIVE_SECONDS
        Mode.QUESTION -> TIMEOUT_INTERACTIVE_SECONDS
    }

    private fun contextCharLimitFor(mode: Mode): Int = when (mode) {
        Mode.SUMMARISE -> MAX_CONTEXT_CHARS_SUMMARISE
        Mode.REWRITE -> MAX_CONTEXT_CHARS_REWRITE
        Mode.QUESTION -> QUESTION_CONTEXT_CHAR_LIMIT
    }

    private fun partialResultForTimeout(mode: Mode, partial: String, timeoutSeconds: Long): String =
        when (mode) {
            Mode.QUESTION ->
                partial.trimEnd() + "\n\n(Stopped after ${timeoutSeconds} seconds; partial answer saved.)"
            Mode.REWRITE,
            Mode.SUMMARISE -> partial
        }

    private class InferenceTimeoutException(
        val mode: Mode,
        val timeoutSeconds: Long,
        cause: TimeoutException,
    ) : RuntimeException(
        "MLC $mode inference timed out after $timeoutSeconds seconds",
        cause,
    )

    companion object {
        private const val TAG = "LlamaEngine"
        private const val MIN_MAX_TOKENS       = 1
        private const val MAX_TOKENS_SUMMARISE = 64
        private const val MAX_TOKENS_REWRITE   = 160
        private const val MAX_TOKENS_QUESTION  = 128
        private const val MAX_CONTEXT_CHARS_SUMMARISE = 900
        private const val MAX_CONTEXT_CHARS_REWRITE = 2_000
        internal const val QUESTION_CONTEXT_CHAR_LIMIT = 1_000
        private const val MAX_QUESTION_CHARS   = 500
        // A warm 3B response can take several seconds on an x86 emulator. Keep
        // the same bounded interactive budget so real output is not discarded
        // moments before its first token arrives.
        private const val TIMEOUT_SUMMARISE_SECONDS = 30L
        private const val TIMEOUT_INTERACTIVE_SECONDS = 30L
        private const val WARMUP_TIMEOUT_SECONDS = 90L
        private const val MAX_CPU_THREADS = 8
        private const val BYTES_PER_GIB = 1_073_741_824.0
        private const val THERMAL_HEADROOM_THROTTLE_THRESHOLD = 0.15f
    }
}
