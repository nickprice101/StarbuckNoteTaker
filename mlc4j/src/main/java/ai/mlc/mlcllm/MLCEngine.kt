package ai.mlc.mlcllm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * On-device MLC LLM Android engine backed by [JSONFFIEngine] + TVM runtime.
 *
 * ## Setup prerequisites
 *
 * Before building, two native artifacts must be present:
 *
 * 1. **`libtvm4j_runtime_packed.so`** in `mlc4j/src/main/jniLibs/<abi>/`
 *    - the packed TVM runtime. Run `scripts/fetch_mlc_native.sh` for arm64 or
 *    `TARGET_ABI=x86_64 scripts/fetch_mlc_native.sh` for an x86_64 emulator.
 *
 * 2. **`libLlama-3.2-3B-Instruct-q4f16_0-MLC.so`** in `app/src/main/jniLibs/<abi>/`
 *    - the compiled model kernel library. The Gradle task `buildModelLibSo`
 *    creates this automatically during the build by linking the `.o` files
 *    from the bundled `.tar` asset against the TVM FFI-capable runtime.
 *
 * The ~2 GB model **weights** are NOT bundled in the APK; they are downloaded
 * at runtime.
 *
 * ## Inference flow
 *
 * ```
 * LlamaEngine.ensureEngineLoaded(modelPath)
 *   -> System.load(modelSoPath)
 *   -> MLCEngine.reload("system://llama_q4f16_0", weightsDir)
 *       -> jsonFFIEngine.reload(config)            // loads model via TVM
 *
 * LlamaEngine.generateWithMlc(...)
 *   -> MLCEngine.chat.completions.create(request, callback)
 *       -> jsonFFIEngine.chatCompletion(json, id)  // streams tokens via callback
 * ```
 */
class MLCEngine(deviceType: String = "opencl") {

    /**
     * Capacity limits for a single-user on-device engine. Explicit limits are
     * important on phones because MLC's interactive preset otherwise expands
     * buffers to the model's full (very large) context window.
     */
    data class RuntimeConfig(
        val gpuMemoryUtilization: Double,
        val maxNumSequence: Int,
        val maxTotalSequenceLength: Int,
        val maxSingleSequenceLength: Int,
        val prefillChunkSize: Int,
        val prefixCacheMaxNumRecyclingSeqs: Int = 1,
    )

    private val TAG = "MLCEngine"

    private val jsonFFIEngine = JSONFFIEngine(deviceType)

    /** Maps in-flight request IDs to their completion latches and stream callbacks. */
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val backgroundFailure = AtomicReference<Throwable?>(null)

    val chat: Chat = Chat()

    init {
        jsonFFIEngine.initBackgroundEngine { resultJson ->
            dispatchStreamResult(resultJson)
        }
        thread(isDaemon = true, name = "mlc-background-loop", priority = Thread.NORM_PRIORITY) {
            runBackgroundLoopSafely()
        }
        thread(isDaemon = true, name = "mlc-stream-back-loop") {
            runStreamBackLoopSafely()
        }
    }

    /**
     * Loads (or hot-swaps) the model.
     *
     * @param modelLib  Absolute path to the compiled model `.so`, or a
     *                  `system://name` handle for pre-loaded system-lib modules.
     * @param modelPath Absolute path to the model weights directory.
     * @param mode      MLC engine mode: "interactive", "local", or "server".
     */
    fun reload(
        modelLib: String,
        modelPath: String,
        mode: String = "interactive",
        runtimeConfig: RuntimeConfig? = null,
    ) {
        backgroundFailure.set(null)
        val config = JSONObject().apply {
            put("model", modelPath)
            put("model_lib", modelLib)
            put("mode", mode)
            runtimeConfig?.let { tuning ->
                put("gpu_memory_utilization", tuning.gpuMemoryUtilization)
                put("max_num_sequence", tuning.maxNumSequence)
                put("max_total_sequence_length", tuning.maxTotalSequenceLength)
                put("max_single_sequence_length", tuning.maxSingleSequenceLength)
                put("prefill_chunk_size", tuning.prefillChunkSize)
                put(
                    "prefix_cache_max_num_recycling_seqs",
                    tuning.prefixCacheMaxNumRecyclingSeqs,
                )
            }
        }.toString()
        Log.i(TAG, "Reloading engine: lib=$modelLib mode=$mode tuning=$runtimeConfig")
        jsonFFIEngine.reload(config)
        throwIfBackgroundFailed()
        Log.i(TAG, "Engine reload complete")
    }

    /**
     * Unloads the current model, stops background threads, and frees GPU memory.
     */
    fun unload() {
        runCatching { jsonFFIEngine.exitBackgroundLoop() }
            .onFailure { Log.w(TAG, "Failed to signal MLC background loop exit", it) }
        runNativeTeardown("unload") { jsonFFIEngine.unload() }
    }

    /**
     * Resets the KV-cache / conversation history without reloading the model.
     */
    fun reset() {
        runCatching { jsonFFIEngine.reset() }
    }

    // ------------------------------------------------------------------
    // Internal: stream-callback dispatch
    // ------------------------------------------------------------------

    /**
     * Parses the list of response chunks in [resultJson], dispatches each to
     * the matching [PendingRequest], and signals completion when a
     * `finish_reason` is observed.
     */
    private fun dispatchStreamResult(resultJson: String?) {
        if (resultJson == null) return
        Log.d(TAG, "Received completion stream callback (${resultJson.length} chars)")
        try {
            // The callback delivers a JSON array of ChatCompletionStreamResponse objects.
            val arr: org.json.JSONArray = when {
                resultJson.trimStart().startsWith('[') -> org.json.JSONArray(resultJson)
                else -> org.json.JSONArray().apply { put(org.json.JSONObject(resultJson)) }
            }
            for (i in 0 until arr.length()) {
                val chunk = arr.getJSONObject(i)
                val requestId = chunk.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val req = pending[requestId] ?: continue

                req.callback(parseChunk(chunk))

                // MLC guarantees that the final usage-only block is last. Treat it as
                // terminal as well as finish_reason so completion cannot wait for its
                // timeout if a runtime emits the finish and usage blocks separately.
                if (chunk.has("usage") && !chunk.isNull("usage")) {
                    pending.remove(requestId)
                    req.latch.countDown()
                    continue
                }

                // Release the latch when a terminal finish_reason is present.
                val choices = chunk.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val finishReason = choices.getJSONObject(0).opt("finish_reason")
                    if (finishReason != null && finishReason != org.json.JSONObject.NULL) {
                        pending.remove(requestId)
                        req.latch.countDown()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream callback parse error: ${e.message}\nJSON: $resultJson")
        }
    }

    // ------------------------------------------------------------------
    // Nested chat API — mirrors the OpenAI SDK structure used by LlamaEngine
    // ------------------------------------------------------------------

    inner class Chat {
        val completions: Completions = Completions()
    }

    inner class Completions {

        /**
         * Submits a streaming chat-completion request and blocks until done.
         *
         * The [callback] is invoked on the stream-back thread for each token
         * delta.  The method returns once a terminal `finish_reason` is
         * received or the timeout expires.
         */
        fun create(
            request: OpenAIProtocol.ChatCompletionRequest,
            timeoutSeconds: Long = DEFAULT_COMPLETION_TIMEOUT_SECS,
            callback: (OpenAIProtocol.ChatCompletionStreamResponse) -> Unit,
        ) {
            val requestId = UUID.randomUUID().toString()
            val latch = CountDownLatch(1)
            pending[requestId] = PendingRequest(latch, callback)

            val requestJson = serializeRequest(request, requestId)
            Log.d(TAG, "Submitting completion requestId=$requestId")
            throwIfBackgroundFailed()
            try {
                val accepted = jsonFFIEngine.chatCompletion(requestJson, requestId)
                if (!accepted) {
                    throw IllegalArgumentException(
                        "MLC rejected completion request: ${jsonFFIEngine.getLastError()}",
                    )
                }
                Log.d(TAG, "Completion accepted requestId=$requestId")

                val boundedTimeout = timeoutSeconds.coerceAtLeast(1L)
                val finished = latch.await(boundedTimeout, TimeUnit.SECONDS)
                throwIfBackgroundFailed()
                if (!finished) {
                    Log.w(TAG, "Completion timed out; aborting requestId=$requestId")
                    runCatching { jsonFFIEngine.abort(requestId) }
                        .onFailure { Log.w(TAG, "Failed to abort timed-out requestId=$requestId", it) }
                    throw TimeoutException(
                        "MLC completion timed out after $boundedTimeout seconds"
                    )
                }
            } finally {
                pending.remove(requestId)
            }
        }
    }

    private fun runBackgroundLoopSafely() {
        try {
            jsonFFIEngine.runBackgroundLoop()
        } catch (t: Throwable) {
            recordBackgroundFailure("MLC background loop failed", t)
        }
    }

    private fun runStreamBackLoopSafely() {
        try {
            jsonFFIEngine.runBackgroundStreamBackLoop()
        } catch (t: Throwable) {
            recordBackgroundFailure("MLC stream-back loop failed", t)
        }
    }

    private fun recordBackgroundFailure(message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
        backgroundFailure.set(throwable)
        pending.values.forEach { it.latch.countDown() }
    }

    private fun throwIfBackgroundFailed() {
        val failure = backgroundFailure.get() ?: return
        throw IllegalStateException("MLC engine background thread failed: ${failure.message}", failure)
    }

    private fun runNativeTeardown(name: String, block: () -> Unit) {
        val failure = AtomicReference<Throwable?>(null)
        val worker = thread(isDaemon = true, name = "mlc-$name-teardown") {
            try {
                block()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        worker.join(NATIVE_TEARDOWN_TIMEOUT_MS)
        failure.get()?.let { Log.w(TAG, "MLC native $name failed", it) }
        if (worker.isAlive) {
            Log.w(TAG, "MLC native $name did not finish within ${NATIVE_TEARDOWN_TIMEOUT_MS}ms")
        }
    }

    // ------------------------------------------------------------------
    // JSON serialization helpers
    // ------------------------------------------------------------------

    private fun serializeRequest(
        request: OpenAIProtocol.ChatCompletionRequest,
        requestId: String,
    ): String {
        val obj = JSONObject()
        obj.put("id", requestId)
        val messagesArr = JSONArray()
        for (msg in request.messages) {
            messagesArr.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }
        obj.put("messages", messagesArr)
        obj.put("stream", request.stream)
        request.model?.let { obj.put("model", it) }
        request.maxTokens?.let { obj.put("max_tokens", it) }
        request.temperature?.let { obj.put("temperature", it.toDouble()) }
        request.topP?.let { obj.put("top_p", it.toDouble()) }
        if (request.stop.isNotEmpty()) {
            val stopArr = JSONArray()
            request.stop.forEach { stopArr.put(it) }
            obj.put("stop", stopArr)
        }
        return obj.toString()
    }

    private fun parseChunk(chunk: JSONObject): OpenAIProtocol.ChatCompletionStreamResponse {
        val choicesArr = chunk.optJSONArray("choices")
            ?: return OpenAIProtocol.ChatCompletionStreamResponse()
        val choices = (0 until choicesArr.length()).map { i ->
            val c = choicesArr.getJSONObject(i)
            val delta = c.optJSONObject("delta")
            val content = if (delta != null && delta.has("content") && !delta.isNull("content")) {
                delta.getString("content")
            } else {
                null
            }
            OpenAIProtocol.ChatCompletionStreamResponseChoice(
                delta = OpenAIProtocol.ChatCompletionMessageDelta(content = content),
            )
        }
        return OpenAIProtocol.ChatCompletionStreamResponse(choices = choices)
    }

    // ------------------------------------------------------------------

    private data class PendingRequest(
        val latch: CountDownLatch,
        val callback: (OpenAIProtocol.ChatCompletionStreamResponse) -> Unit,
    )

    companion object {
        private const val DEFAULT_COMPLETION_TIMEOUT_SECS = 30L
        private const val NATIVE_TEARDOWN_TIMEOUT_MS = 5_000L
    }
}
