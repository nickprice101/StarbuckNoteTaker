package ai.mlc.mlcllm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real on-device MLC LLM Android engine backed by the TVM runtime
 * (`libtvm4j_runtime_packed.so`) via [ChatModule].
 *
 * ## Setup prerequisites
 *
 * Before building, the two native artifacts must be present on disk:
 *
 * 1. **`libtvm4j_runtime_packed.so`** in `app/src/main/jniLibs/arm64-v8a/`
 *    — the packed TVM runtime that provides all JNI functions.
 *    Run `scripts/fetch_mlc_native.sh` to download it from the MLC LLM
 *    binary release APK (`Android-09262024`).
 *
 * 2. **`app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar`**
 *    — the compiled MLC system-lib archive bundled inside the APK.
 *    Run `scripts/compile_model_tar.sh` to build it from the Llama 3.2 3B
 *    quantised weights using `mlc_llm compile --system-lib`.
 *
 * The ~2 GB model **weights** are NOT bundled in the APK; they are downloaded
 * at runtime via the Settings screen (`LlamaModelManager.downloadModel`).
 *
 * ## Inference flow
 *
 * ```
 * LlamaEngine.ensureEngineLoaded(modelPath)
 *   → LlamaModelManager.extractModelLibIfNeeded()   // extracts .tar → dir path
 *   → MLCEngine.reload(modelLibDir, weightsDir)
 *       → ChatModule.chatCreate(modelLibDir, weightsDir, config)
 *           → native: loads .o files + weight shards
 *
 * LlamaEngine.generateWithMlc(...)
 *   → MLCEngine.chat.completions.create(request, callback)
 *       → ChatModule.chatCompletion(handle, requestJson, callback)
 *           → native: streams token deltas to callback
 * ```
 *
 * Source reference: https://github.com/mlc-ai/mlc-llm/tree/main/android/mlc4j
 */
class MLCEngine {

    private val TAG = "MLCEngine"

    /** Non-zero when the model has been loaded via [reload]. */
    private var chatHandle: Long = 0L

    val chat: Chat = Chat()

    /**
     * Loads the model library and weights.
     *
     * @param modelLib  Absolute path to the extracted system-lib directory
     *                  (contains `lib0.o`, `llama_q4f16_0_devc.o`).
     * @param modelPath Absolute path to the model weights directory
     *                  (contains `mlc-chat-config.json`, weight shards, etc.).
     * @throws RuntimeException if the native engine cannot be initialised.
     */
    fun reload(modelLib: String, modelPath: String) {
        if (chatHandle != 0L) {
            Log.d(TAG, "Unloading existing engine before reload")
            releaseHandle()
        }
        Log.i(TAG, "Loading model: lib=$modelLib  weights=$modelPath")
        chatHandle = ChatModule.chatCreate(modelLib, modelPath, "{}")
        if (chatHandle == 0L) {
            throw RuntimeException(
                "ChatModule.chatCreate returned a null handle. " +
                "Verify that the model library directory contains the compiled " +
                ".o files and that the weights directory is complete."
            )
        }
        Log.i(TAG, "Model loaded successfully (handle=$chatHandle)")
    }

    /** Releases the native engine and frees model memory. */
    fun unload() {
        releaseHandle()
    }

    /**
     * Clears the conversation history without reloading the model weights.
     * Safe to call between independent summarise/rewrite/question calls.
     */
    fun reset() {
        if (chatHandle != 0L) {
            ChatModule.chatReset(chatHandle)
        }
    }

    /** Releases the native handle, if one is held. */
    private fun releaseHandle() {
        if (chatHandle != 0L) {
            ChatModule.chatUnload(chatHandle)
            chatHandle = 0L
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
         * Runs a streaming chat-completion request.
         *
         * The [callback] is invoked once per token delta on the calling thread
         * as the model generates text.  The call blocks until generation is
         * complete.
         *
         * @param request  The [OpenAIProtocol.ChatCompletionRequest] to send.
         * @param callback Receives each [OpenAIProtocol.ChatCompletionStreamResponse] chunk.
         * @throws IllegalStateException if [reload] has not been called first.
         */
        fun create(
            request: OpenAIProtocol.ChatCompletionRequest,
            callback: (OpenAIProtocol.ChatCompletionStreamResponse) -> Unit,
        ) {
            check(chatHandle != 0L) {
                "MLCEngine.chat.completions.create called before MLCEngine.reload(). " +
                "Ensure the model is loaded before attempting inference."
            }

            val requestJson = serializeRequest(request)
            Log.d(TAG, "Starting completion (handle=$chatHandle)")

            ChatModule.chatCompletion(chatHandle, requestJson) { deltaJson ->
                val response = parseStreamResponse(deltaJson)
                callback(response)
            }
        }
    }

    // ------------------------------------------------------------------
    // JSON serialization helpers
    // Uses org.json (bundled with Android) — no extra dependency required.
    // ------------------------------------------------------------------

    private fun serializeRequest(request: OpenAIProtocol.ChatCompletionRequest): String {
        val obj = JSONObject()
        val messagesArr = JSONArray()
        for (msg in request.messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            messagesArr.put(msgObj)
        }
        obj.put("messages", messagesArr)
        obj.put("stream", request.stream)
        if (request.model != null) obj.put("model", request.model)
        if (request.maxTokens != null) obj.put("max_tokens", request.maxTokens)
        return obj.toString()
    }

    /**
     * Parses a JSON delta string from the native streaming callback into a
     * [OpenAIProtocol.ChatCompletionStreamResponse].
     *
     * Expected format:
     * ```json
     * {"choices":[{"delta":{"content":"token"},"finish_reason":null}]}
     * ```
     * Malformed or unexpected JSON is silently converted to an empty response
     * rather than crashing the generation loop.
     */
    private fun parseStreamResponse(json: String): OpenAIProtocol.ChatCompletionStreamResponse {
        return try {
            val obj = JSONObject(json)
            val choicesArr = obj.optJSONArray("choices")
            if (choicesArr == null || choicesArr.length() == 0) {
                return OpenAIProtocol.ChatCompletionStreamResponse()
            }
            val choices = (0 until choicesArr.length()).map { i ->
                val choiceObj = choicesArr.getJSONObject(i)
                val deltaObj = choiceObj.optJSONObject("delta")
                val content = deltaObj?.optString("content", null)
                OpenAIProtocol.ChatCompletionStreamResponseChoice(
                    delta = OpenAIProtocol.ChatCompletionMessageDelta(content = content),
                )
            }
            OpenAIProtocol.ChatCompletionStreamResponse(choices = choices)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stream response JSON: $json", e)
            OpenAIProtocol.ChatCompletionStreamResponse()
        }
    }
}
