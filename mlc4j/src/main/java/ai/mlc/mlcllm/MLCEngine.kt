package ai.mlc.mlcllm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        thread(isDaemon = true, name = "mlc-background-loop", priority = Thread.MAX_PRIORITY) {
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
     */
    fun reload(modelLib: String, modelPath: String) {
        backgroundFailure.set(null)
        val config = JSONObject().apply {
            put("model", modelPath)
            put("model_lib", modelLib)
            put("mode", "interactive")
        }.toString()
        Log.i(TAG, "Reloading engine: lib=$modelLib")
        jsonFFIEngine.reload(config)
        throwIfBackgroundFailed()
        Log.i(TAG, "Engine reload complete")
    }

    /**
     * Unloads the current model, stops background threads, and frees GPU memory.
     */
    fun unload() {
        runCatching { jsonFFIEngine.unload() }
        runCatching { jsonFFIEngine.exitBackgroundLoop() }
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
            callback: (OpenAIProtocol.ChatCompletionStreamResponse) -> Unit,
        ) {
            val requestId = UUID.randomUUID().toString()
            val latch = CountDownLatch(1)
            pending[requestId] = PendingRequest(latch, callback)

            val requestJson = serializeRequest(request, requestId)
            Log.d(TAG, "Submitting completion requestId=$requestId")
            throwIfBackgroundFailed()
            jsonFFIEngine.chatCompletion(requestJson, requestId)

            val finished = latch.await(COMPLETION_TIMEOUT_SECS, TimeUnit.SECONDS)
            pending.remove(requestId)
            throwIfBackgroundFailed()
            if (!finished) {
                Log.w(TAG, "Completion timed out (requestId=$requestId)")
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
        private const val COMPLETION_TIMEOUT_SECS = 120L
    }
}
