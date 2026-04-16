package ai.mlc.mlcllm

import android.util.Log
import org.json.JSONObject

/**
 * JNI bridge to the MLC LLM TVM runtime packed shared library
 * (`libtvm4j_runtime_packed.so`).
 *
 * All native methods are declared as `@JvmStatic` on the companion object,
 * which causes the Kotlin compiler to emit static methods directly on the
 * `ai.mlc.mlcllm.ChatModule` class.  The JNI symbols exported by
 * `libtvm4j_runtime_packed.so` therefore follow the naming convention:
 *
 *   `Java_ai_mlc_mlcllm_ChatModule_<methodName>`
 *
 * This class is an internal implementation detail; callers should use
 * [MLCEngine] which provides the high-level chat-completion API.
 *
 * ## System-lib `.tar` flow
 *
 * The [modelLib] argument passed to [chatCreate] is the **directory path** of
 * the extracted model library archive (see `LlamaModelManager.extractModelLibIfNeeded`).
 * That directory contains the TVM system-lib object files (`lib0.o`,
 * `llama_q4f16_0_devc.o`) compiled by:
 *
 * ```
 * mlc_llm compile \
 *   ./Llama-3.2-3B-Instruct-q4f16_0-MLC \
 *   --target android \
 *   --device android:arm64-v8a \
 *   --system-lib \
 *   -o Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar
 * ```
 *
 * The TVM runtime inside `libtvm4j_runtime_packed.so` loads the `.o` files
 * from that directory at runtime using its built-in dynamic linker.
 */
class ChatModule {

    companion object {

        private const val TAG = "ChatModule"

        init {
            try {
                System.loadLibrary("tvm4j_runtime_packed")
                Log.i(TAG, "libtvm4j_runtime_packed.so loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                // Re-throw with a descriptive message so it is clear what is missing.
                throw UnsatisfiedLinkError(
                    "Failed to load libtvm4j_runtime_packed.so. " +
                    "Run scripts/fetch_mlc_native.sh to download it and ensure " +
                    "it is placed in app/src/main/jniLibs/arm64-v8a/ before building. " +
                    "Original error: ${e.message}"
                )
            }
        }

        /**
         * Creates a new MLC chat engine handle.
         *
         * @param modelLib     Absolute path to the directory that contains the
         *                     compiled TVM system-lib object files extracted from
         *                     the `.tar` asset.
         * @param modelPath    Absolute path to the model weights directory
         *                     (downloaded from HuggingFace by `LlamaModelManager`).
         * @param engineConfig JSON string with engine hyper-parameters.
         *                     Pass `"{}"` to use the defaults from `mlc-chat-config.json`.
         * @return Non-zero engine handle on success, 0 on failure.
         */
        @JvmStatic
        external fun chatCreate(modelLib: String, modelPath: String, engineConfig: String): Long

        /**
         * Releases all resources held by the engine identified by [chatHandle].
         * After this call [chatHandle] must not be used again.
         */
        @JvmStatic
        external fun chatUnload(chatHandle: Long)

        /**
         * Runs a streaming chat-completion request.
         *
         * The native code calls [callback] synchronously on the calling thread
         * for each token delta as it is generated.  Each invocation passes a
         * JSON-serialised `ChatCompletionStreamResponse` fragment.  The call
         * blocks until generation is complete or an error occurs.
         *
         * @param chatHandle Engine handle from [chatCreate].
         * @param input      JSON-serialised `ChatCompletionRequest`.
         * @param callback   Receives each streaming response chunk.
         */
        @JvmStatic
        external fun chatCompletion(chatHandle: Long, input: String, callback: ChatCompletionCallback)

        /**
         * Resets the conversation history inside the engine without unloading
         * the model weights.  Use before starting a new independent conversation.
         *
         * @param chatHandle Engine handle from [chatCreate].
         */
        @JvmStatic
        external fun chatReset(chatHandle: Long)

        /**
         * Callback interface invoked by the native layer for each streaming token.
         *
         * The JNI code inside `libtvm4j_runtime_packed.so` locates this method
         * by name and descriptor.  The method name **must** remain `onChatCompletion`
         * and the signature **must** be `(String) -> Unit` to match the JNI lookup.
         */
        fun interface ChatCompletionCallback {
            /**
             * Called once per token delta with a JSON-serialised
             * `ChatCompletionStreamResponse` fragment.
             *
             * @param response JSON string, e.g.
             *   `{"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}`
             */
            fun onChatCompletion(response: String)
        }
    }
}
