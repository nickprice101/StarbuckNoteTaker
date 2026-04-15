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
     *
     * @param modelLib  path to (or name of) the compiled model library
     * @param modelPath path to the model weights directory
     * @throws UnsupportedOperationException always — native TVM libraries are not present
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
     * Stub completion call. Always throws because [MLCEngine.reload] will have
     * already thrown before this point can be reached; the exception is caught
     * by the `runCatching` in [LlamaEngine][com.example.starbucknotetaker.LlamaEngine]
     * and routed to the rule-based fallback.
     *
     * @throws UnsupportedOperationException always — native TVM libraries are not present
     */
    fun create(
        request: OpenAIProtocol.ChatCompletionRequest,
        callback: (OpenAIProtocol.ChatCompletionStreamResponse) -> Unit,
    ) {
        throw UnsupportedOperationException(
            "MLC LLM native library is not available in this build."
        )
    }
}
