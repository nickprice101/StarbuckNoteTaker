package com.example.starbucknotetaker

import android.content.Context
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

sealed interface AgentTurnUpdate {
    data class Partial(val text: String) : AgentTurnUpdate
    data class Complete(val text: String) : AgentTurnUpdate
}

/** ADK-owned note agent workflows backed by [MlcAdkModel]. */
internal object NoteAiAgent {
    const val AGENT_NAME = "starbuck_note_agent"
    private const val APP_NAME = "StarbuckNoteTaker"
    private const val USER_ID = "local-note-user"
    private const val REFORMAT_CHUNK_CHARS = 2_700

    suspend fun reformat(
        context: Context,
        noteText: String,
        taskId: String = UUID.randomUUID().toString(),
        model: Model = MlcAdkModel(context.applicationContext),
    ): String {
        val source = noteText.trim()
        require(source.isNotBlank()) { "The note has no text to reformat." }
        (model as? MlcAdkModel)?.requireAvailable()
        val reformatInstruction = Instruction(AiAgentPrompts.load(context).reformatting)
        val chunkChars = (model as? MlcAdkModel)?.recommendedReformatChunkChars
            ?: REFORMAT_CHUNK_CHARS
        val chunks = NoteTextChunker.chunk(source, chunkChars)
        val total = chunks.size
        val reformatted = chunks.mapIndexed { index, chunk ->
            val agent = LlmAgent(
                name = AGENT_NAME,
                description = "Corrects and restructures notes without changing their meaning.",
                model = model,
                instruction = reformatInstruction,
                includeContents = LlmAgent.IncludeContents.NONE,
                generateContentConfig = GenerateContentConfig(
                    temperature = 0.1f,
                    topP = 0.9f,
                    maxOutputTokens = 768,
                ),
                maxSteps = 1,
            )
            val runner = InMemoryRunner(agent = agent, appName = APP_NAME)
            val prompt = buildString {
                if (total > 1) {
                    appendLine("This is fragment ${index + 1} of $total. Format it as a continuous part of the note.")
                }
                appendLine("<note>")
                appendLine(chunk)
                append("</note>")
            }
            val output = runner.finalText(
                sessionId = "$taskId-$index",
                message = prompt,
                streaming = false,
            )
            validateReformat(chunk, output)
        }
        val deduplicated = ReformattedNoteDeduplicator.removeRepeatedContent(
            reformatted.joinToString("\n\n"),
        )
        return validateReformat(source, deduplicated)
    }

    fun conversation(
        context: Context,
        sessionId: String,
        noteContext: String,
        model: Model = MlcAdkModel(context.applicationContext),
    ): NoteConversationAgent = NoteConversationAgent(
        model = model,
        sessionId = sessionId,
        noteContext = noteContext,
        systemInstruction = AiAgentPrompts.load(context).chatbot,
    )

    private fun validateReformat(source: String, output: String): String {
        val cleaned = output
            .trim()
            .removeSurrounding("```markdown", "```")
            .removeSurrounding("```", "```")
            .trim()
        require(cleaned.isNotBlank()) { "The AI agent returned an empty reformat." }
        val sourceWords = source.wordCount()
        val outputWords = cleaned.wordCount()
        if (sourceWords >= 12) {
            require(outputWords >= ceil(sourceWords * 0.45).toInt()) {
                "The AI reformat omitted too much of the original note, so it was not applied."
            }
        }
        return cleaned
    }

    private fun String.wordCount(): Int =
        trim().split(Regex("\\s+")).count { it.isNotBlank() }

    private suspend fun InMemoryRunner.finalText(
        sessionId: String,
        message: String,
        streaming: Boolean,
    ): String {
        var final = ""
        withTimeout(AGENT_TURN_TIMEOUT_MS) {
            runAsync(
                userId = USER_ID,
                sessionId = sessionId,
                newMessage = Content(role = Role.USER, parts = listOf(Part(text = message))),
                runConfig = RunConfig(
                    streamingMode = if (streaming) StreamingMode.SSE else StreamingMode.NONE,
                ),
            ).collect { event ->
                if (event.author == AGENT_NAME && !event.partial) {
                    final = event.content?.parts?.mapNotNull { it.text }?.joinToString("").orEmpty()
                }
                event.errorMessage?.takeIf { it.isNotBlank() }?.let { error(error = it) }
            }
        }
        return final.trim()
    }

    private fun error(error: String): Nothing = throw IllegalStateException(error)

    internal const val AGENT_TURN_TIMEOUT_MS = 75_000L
}

internal class NoteConversationAgent(
    private val model: Model,
    private val sessionId: String,
    private val noteContext: String,
    private val systemInstruction: String,
) {
    private val sessionService = InMemorySessionService()
    private val recentTurns = ArrayDeque<ConversationTurn>()
    private val userPromptCharLimit =
        ((model as? MlcAdkModel)?.promptCharLimit ?: DEFAULT_MODEL_PROMPT_CHARS) -
            systemInstruction.length - MODEL_PROMPT_MARGIN_CHARS
    private val agent = LlmAgent(
        name = NoteAiAgent.AGENT_NAME,
        description = "A private conversational assistant for the note currently being edited.",
        model = model,
        instruction = Instruction(systemInstruction),
        includeContents = LlmAgent.IncludeContents.NONE,
        generateContentConfig = GenerateContentConfig(
            temperature = 0.3f,
            topP = 0.9f,
            maxOutputTokens = 512,
        ),
        maxSteps = 1,
    )
    private val runner = InMemoryRunner(
        agent = agent,
        appName = "StarbuckNoteTaker",
        sessionService = sessionService,
    )

    fun send(message: String): Flow<AgentTurnUpdate> = flow {
        val trimmed = message.trim()
        require(trimmed.isNotBlank()) { "Message cannot be empty." }
        (model as? MlcAdkModel)?.requireAvailable()
        val turnPrompt = AgentContextPromptBuilder.build(
            currentNote = noteContext,
            recentConversation = recentTurns.render(),
            userRequest = trimmed,
            maxChars = userPromptCharLimit.coerceAtLeast(MIN_USER_PROMPT_CHARS),
        )
        val accumulated = StringBuilder()
        var finalText = ""
        withTimeout(NoteAiAgent.AGENT_TURN_TIMEOUT_MS) {
            runner.runAsync(
                userId = "local-note-user",
                sessionId = sessionId,
                newMessage = Content(role = Role.USER, parts = listOf(Part(text = turnPrompt))),
                runConfig = RunConfig(streamingMode = StreamingMode.SSE),
            ).collect { event ->
                event.errorMessage?.takeIf { it.isNotBlank() }?.let { error(it) }
                if (event.author != NoteAiAgent.AGENT_NAME) return@collect
                val text = event.content?.parts?.mapNotNull { it.text }?.joinToString("").orEmpty()
                if (event.partial) {
                    accumulated.append(text)
                    emit(AgentTurnUpdate.Partial(accumulated.toString()))
                } else {
                    finalText = text.ifBlank { accumulated.toString() }.trim()
                }
            }
        }
        require(finalText.isNotBlank()) { "The on-device agent returned no reply." }
        recentTurns += ConversationTurn(user = trimmed, assistant = finalText)
        while (recentTurns.size > MAX_RECENT_EXCHANGES) recentTurns.removeFirst()
        emit(AgentTurnUpdate.Complete(finalText))
    }

    private fun Collection<ConversationTurn>.render(): String = joinToString("\n\n") { turn ->
        "User: ${turn.user}\nAssistant: ${turn.assistant}"
    }

    private data class ConversationTurn(val user: String, val assistant: String)

    private companion object {
        const val DEFAULT_MODEL_PROMPT_CHARS = 4_000
        const val MODEL_PROMPT_MARGIN_CHARS = 64
        const val MIN_USER_PROMPT_CHARS = 320
        const val MAX_RECENT_EXCHANGES = 4
    }
}

/** Removes model-created exact repetitions while leaving unique note information untouched. */
internal object ReformattedNoteDeduplicator {
    private val markdownPrefix = Regex("^\\s*(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+|>\\s+)")
    private val markdownDecoration = Regex("[`*_~]")
    private val nonSemanticCharacters = Regex("[^\\p{L}\\p{N}]+")

    fun removeRepeatedContent(text: String): String {
        val seen = mutableSetOf<String>()
        val kept = mutableListOf<String>()
        var insideCodeFence = false

        text.replace("\r\n", "\n").lines().forEach { line ->
            if (line.trimStart().startsWith("```")) {
                insideCodeFence = !insideCodeFence
                kept += line.trimEnd()
                return@forEach
            }
            if (line.isBlank()) {
                if (kept.lastOrNull()?.isNotBlank() == true) kept += ""
                return@forEach
            }
            if (insideCodeFence) {
                kept += line.trimEnd()
                return@forEach
            }
            val key = line
                .replace(markdownPrefix, "")
                .replace(markdownDecoration, "")
                .lowercase()
                .replace(nonSemanticCharacters, " ")
                .trim()
            if (key.length < MIN_DEDUPLICATION_KEY_CHARS || seen.add(key)) {
                kept += line.trimEnd()
            }
        }

        return kept.joinToString("\n").trim()
    }

    private const val MIN_DEDUPLICATION_KEY_CHARS = 4
}

internal object NoteTextChunker {
    fun chunk(text: String, maxChars: Int): List<String> {
        require(maxChars >= 400)
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.length <= maxChars) return listOf(normalized)
        val paragraphs = normalized.split(Regex("\\n{2,}"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            current.toString().trim().takeIf { it.isNotBlank() }?.let(chunks::add)
            current.clear()
        }

        paragraphs.forEach { paragraph ->
            if (paragraph.length > maxChars) {
                flush()
                paragraph.lines().fold(StringBuilder()) { lineChunk, line ->
                    if (lineChunk.isNotEmpty() && lineChunk.length + line.length + 1 > maxChars) {
                        chunks += lineChunk.toString().trim()
                        lineChunk.clear()
                    }
                    if (line.length > maxChars) {
                        line.chunked(maxChars).forEach { piece ->
                            if (lineChunk.isNotEmpty()) {
                                chunks += lineChunk.toString().trim()
                                lineChunk.clear()
                            }
                            chunks += piece.trim()
                        }
                    } else {
                        if (lineChunk.isNotEmpty()) lineChunk.append('\n')
                        lineChunk.append(line)
                    }
                    lineChunk
                }.toString().trim().takeIf { it.isNotBlank() }?.let(chunks::add)
            } else if (current.isNotEmpty() && current.length + paragraph.length + 2 > maxChars) {
                flush()
                current.append(paragraph)
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            }
        }
        flush()
        return chunks
    }
}
