package ai.mlc.mlcllm

/**
 * Stub implementation of the MLC LLM Android engine.
 *
 * The real implementation requires prebuilt TVM native libraries that are not
 * bundled in this repository.  All methods that touch the native layer throw
 * [UnsupportedOperationException], which the app's [LlamaEngine] catches via
 * `runCatching` and routes to the rule-based fallback path.
 *
 * Source reference: https://github.com/mlc-ai/mlc-llm/tree/main/android/mlc4j
 */
class MLCEngine {

    val chat: Chat = Chat()

    /**
     * Loads the model.
     * @param modelLib  path to (or name of) the compiled model library
     * @param modelPath path to the model weights directory
     */
    fun reload(modelLib: String, modelPath: String) {
        throw UnsupportedOperationException(
            "MLC LLM native library is not available in this build. " +
            "On-device Llama inference requires prebuilt TVM native libraries."
        )
    }

    fun unload() {
        // no-op: engine was never loaded
    }

    fun reset() {
        // no-op
    }
}

class Chat {
    val completions: Completions = Completions()
}

class Completions {
    /**
     * Synchronous, callback-based completion call.
     *
     * The stub never invokes [callback] because the native engine is absent.
     * [MLCEngine.reload] will have already thrown before this is reached.
     */
    fun create(
        request: OpenAIProtocol.ChatCompletionRequest,
        callback: (OpenAIProtocol.ChatCompletionStreamResponse) -> Unit,
    ) {
        // no-op stub — native engine unavailable
    }
}
