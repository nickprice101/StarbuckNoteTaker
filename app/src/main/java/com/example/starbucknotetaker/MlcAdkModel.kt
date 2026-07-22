package com.example.starbucknotetaker

import android.content.Context
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

/** Small seam that makes the ADK adapter testable without loading native model code. */
internal interface LocalChatBackend {
    val promptCharLimit: Int
    val maxOutputTokens: Int

    fun unavailableReason(): String? = null

    suspend fun complete(
        messages: List<LlamaEngine.ChatMessage>,
        taskId: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        onToken: (String) -> Unit,
    ): String
}

private class LlamaChatBackend(context: Context) : LocalChatBackend {
    private val engine = LlamaEngineProvider.acquire(context.applicationContext)

    override val promptCharLimit: Int
        get() = engine.agentPromptCharLimit

    override val maxOutputTokens: Int
        get() = engine.agentMaxOutputTokens

    override fun unavailableReason(): String? = engine.agentUnavailableReason()

    override suspend fun complete(
        messages: List<LlamaEngine.ChatMessage>,
        taskId: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        onToken: (String) -> Unit,
    ): String = engine.completeChat(
        messages = messages,
        taskId = taskId,
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
        onToken = onToken,
    )
}

/**
 * Google ADK [Model] backed by the app's offline LiteRT-LM runtime.
 *
 * ADK remains responsible for instructions, session history, event streaming, and agent turns;
 * this class only translates ADK's provider-neutral request into the local OpenAI-style messages
 * accepted by the local runtime. No note content or chat message leaves the device.
 */
internal class MlcAdkModel(
    private val backend: LocalChatBackend,
) : Model {
    constructor(context: Context) : this(LlamaChatBackend(context))

    override val name: String = "qwen3-0.6b-litertlm-local"

    internal val promptCharLimit: Int
        get() = backend.promptCharLimit

    /** Keeps a complete note fragment inside the prompt on the smaller emulator profile. */
    internal val recommendedReformatChunkChars: Int
        get() = (promptCharLimit - PROMPT_OVERHEAD_CHARS)
            .coerceIn(MIN_REFORMAT_CHUNK_CHARS, MAX_REFORMAT_CHUNK_CHARS)

    internal fun requireAvailable() {
        backend.unavailableReason()?.let { throw LlamaEngine.ModelUnavailableException(it) }
    }

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
        channelFlow {
            val taskId = UUID.randomUUID().toString()
            try {
                val messages = request.toLocalMessages(backend.promptCharLimit)
                val result = withContext(Dispatchers.Default) {
                    backend.complete(
                        messages = messages,
                        taskId = taskId,
                        maxTokens = (request.config.maxOutputTokens ?: backend.maxOutputTokens)
                            .coerceIn(MIN_OUTPUT_TOKENS, backend.maxOutputTokens),
                        temperature = (request.config.temperature ?: DEFAULT_TEMPERATURE)
                            .coerceIn(0f, 1f),
                        topP = (request.config.topP ?: DEFAULT_TOP_P).coerceIn(0.1f, 1f),
                        onToken = { token ->
                            if (stream && token.isNotEmpty()) {
                                trySend(modelResponse(token, partial = true))
                            }
                        },
                    )
                }
                send(modelResponse(result, partial = false))
            } catch (failure: Throwable) {
                // ADK models report provider failures as response values. channelFlow then closes
                // normally, allowing the runner to emit one terminal error event immediately.
                send(
                    LlmResponse(
                        errorMessage = failure.message ?: "On-device model failed",
                        modelVersion = name,
                    ),
                )
            }
        }

    internal fun LlmRequest.toLocalMessages(limit: Int): List<LlamaEngine.ChatMessage> {
        val allMessages = buildList {
            config.systemInstruction?.plainText()?.takeIf { it.isNotBlank() }?.let { instruction ->
                add(LlamaEngine.ChatMessage(role = Role.SYSTEM, content = instruction))
            }
            contents.forEach { content ->
                val text = content.plainText()
                if (text.isNotBlank()) {
                    add(
                        LlamaEngine.ChatMessage(
                            role = when (content.role) {
                                Role.MODEL, "assistant" -> "assistant"
                                Role.SYSTEM -> Role.SYSTEM
                                else -> Role.USER
                            },
                            content = text,
                        ),
                    )
                }
            }
        }
        require(allMessages.isNotEmpty()) { "ADK produced an empty model request" }
        return fitMessagesToBudget(allMessages, limit.coerceAtLeast(MIN_PROMPT_CHARS))
    }

    private fun fitMessagesToBudget(
        messages: List<LlamaEngine.ChatMessage>,
        charLimit: Int,
    ): List<LlamaEngine.ChatMessage> {
        val system = messages.firstOrNull { it.role == Role.SYSTEM }
        val systemText = system?.content.orEmpty().take(MAX_SYSTEM_INSTRUCTION_CHARS)
        var remaining = (charLimit - systemText.length).coerceAtLeast(MIN_USER_MESSAGE_CHARS)
        val selected = ArrayDeque<LlamaEngine.ChatMessage>()
        messages.asReversed().forEach { message ->
            if (message.role == Role.SYSTEM || remaining <= 0) return@forEach
            val text = if (message.content.length <= remaining) {
                message.content
            } else if (selected.isEmpty()) {
                // Always retain the current user turn. Reformatting chunks are sized so this path
                // should be rare; chat history intentionally gives priority to the newest text.
                message.content.take(remaining)
            } else {
                return@forEach
            }
            selected.addFirst(message.copy(content = text))
            remaining -= text.length
        }
        return buildList {
            if (systemText.isNotBlank()) {
                add(LlamaEngine.ChatMessage(Role.SYSTEM, systemText))
            }
            addAll(selected)
        }
    }

    private fun modelResponse(text: String, partial: Boolean): LlmResponse = LlmResponse(
        content = Content(role = Role.MODEL, parts = listOf(Part(text = text))),
        partial = partial,
        modelVersion = name,
    )

    private fun Content.plainText(): String =
        parts.mapNotNull { it.text }.joinToString(separator = "\n").trim()

    private companion object {
        const val DEFAULT_TEMPERATURE = 0.25f
        const val DEFAULT_TOP_P = 0.9f
        const val MIN_OUTPUT_TOKENS = 32
        const val MIN_PROMPT_CHARS = 800
        const val MIN_USER_MESSAGE_CHARS = 320
        const val MAX_SYSTEM_INSTRUCTION_CHARS = 1_400
        const val PROMPT_OVERHEAD_CHARS = 1_200
        const val MIN_REFORMAT_CHUNK_CHARS = 800
        const val MAX_REFORMAT_CHUNK_CHARS = 2_700
    }
}
