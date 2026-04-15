package ai.mlc.mlcllm

/**
 * Subset of the OpenAI-compatible protocol types used by the MLC LLM Android SDK.
 *
 * Only the fields referenced by the app's [LlamaEngine] are included here.
 * The naming follows the conventions used in that file (camelCase for Kotlin
 * idiomatic style; the upstream library uses snake_case in its serialised JSON).
 *
 * Source reference: https://github.com/mlc-ai/mlc-llm/tree/main/android/mlc4j
 */
class OpenAIProtocol {

    data class ChatCompletionRequest(
        val messages: List<ChatCompletionMessage>,
        val model: String? = null,
        val stream: Boolean = true,
        val maxTokens: Int? = null,
    )

    data class ChatCompletionMessage(
        val role: String,
        val content: String,
    )

    data class ChatCompletionStreamResponse(
        val choices: List<ChatCompletionStreamResponseChoice> = emptyList(),
    )

    data class ChatCompletionStreamResponseChoice(
        val delta: ChatCompletionMessageDelta = ChatCompletionMessageDelta(),
    )

    data class ChatCompletionMessageDelta(
        val content: String? = null,
    )
}
