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

/** ADK-owned note agent workflows backed by [QwenAdkModel]. */
internal object NoteAiAgent {
    const val AGENT_NAME = "starbuck_note_agent"
    private const val APP_NAME = "StarbuckNoteTaker"
    private const val USER_ID = "local-note-user"
    private const val REFORMAT_CHUNK_CHARS = 2_700

    suspend fun reformat(
        context: Context,
        noteText: String,
        taskId: String = UUID.randomUUID().toString(),
        model: Model = QwenAdkModel(context.applicationContext),
        userInstruction: String? = null,
        webResearch: String? = null,
    ): String {
        val source = noteText.trim()
        require(source.isNotBlank()) { "The note has no text to reformat." }
        (model as? QwenAdkModel)?.requireAvailable()
        val systemInstruction = buildString {
            append(AiAgentPrompts.load(context).reformatting)
            appendLine()
            appendLine()
            append(
                "Work hierarchically: follow the supplied document plan, preserve every protected " +
                    "fact and structural element, and output Markdown only.",
            )
            userInstruction?.trim()?.takeIf(String::isNotBlank)?.let {
                appendLine()
                append("The user additionally requested: ")
                append(it.take(MAX_USER_REFORMAT_INSTRUCTION_CHARS))
            }
        }
        val reformatInstruction = Instruction(systemInstruction)
        val agent = LlmAgent(
            name = AGENT_NAME,
            description = "Corrects and restructures notes without changing their meaning.",
            model = model,
            instruction = reformatInstruction,
            includeContents = LlmAgent.IncludeContents.NONE,
            generateContentConfig = GenerateContentConfig(
                temperature = 0.1f,
                topP = 0.85f,
                maxOutputTokens = REFORMAT_MAX_OUTPUT_TOKENS,
            ),
            maxSteps = 1,
        )
        val runner = InMemoryRunner(agent = agent, appName = APP_NAME)
        val chunkChars = (model as? QwenAdkModel)?.recommendedReformatChunkChars
            ?: REFORMAT_CHUNK_CHARS
        val chunks = NoteTextChunker.chunkForQwen(
            source,
            maxChars = chunkChars,
            maxTokens = QwenTokenBudget.estimateTokens(source)
                .coerceAtMost(REFORMAT_MAX_INPUT_TOKENS),
        )
        val total = chunks.size
        val outlineSource = NoteTextChunker.outlineSource(source, OUTLINE_MAX_CHARS)
        val documentPlan = runner.finalText(
            sessionId = "$taskId-plan",
            message = buildString {
                appendLine(
                    "Create a concise global formatting plan for this note. Identify its existing " +
                        "purpose, useful headings, list/table opportunities, narrative perspective, " +
                        "and protected elements. Do not rewrite the note. Use at most 120 words.",
                )
                appendLine("<document_outline_source>")
                appendLine(StructuredNoteDocument.describe(outlineSource))
                append("</document_outline_source>")
                webResearch?.trim()?.takeIf(String::isNotBlank)?.let {
                    appendLine()
                    appendLine("<explicit_online_evidence>")
                    appendLine(it.take(MAX_REFORMAT_RESEARCH_CHARS))
                    append("</explicit_online_evidence>")
                }
            },
            streaming = false,
        ).take(MAX_PLAN_CHARS)
        val reformatted = chunks.mapIndexed { index, chunk ->
            val prompt = buildString {
                appendLine("<document_plan>")
                appendLine(documentPlan)
                appendLine("</document_plan>")
                if (total > 1) {
                    appendLine(
                        "This is fragment ${index + 1} of $total. Format it as a continuous part " +
                            "of the planned document. Do not add a fragment title.",
                    )
                }
                appendLine("<structured_note_fragment>")
                appendLine(StructuredNoteDocument.describe(chunk))
                append("</structured_note_fragment>")
            }
            var output = runner.finalText(
                sessionId = "$taskId-$index",
                message = prompt,
                streaming = false,
            )
            output = cleanReformatOutput(output)
            if (AiGroundingValidator.likelyTruncated(output) &&
                output.wordCount() < (chunk.wordCount() * CONTINUATION_WORD_RATIO).toInt()
            ) {
                val continuation = runner.finalText(
                    sessionId = "$taskId-$index-continuation",
                    message = buildString {
                        appendLine(
                            "Continue the reformatted fragment from exactly where the partial output " +
                                "stopped. Return only the missing continuation and do not repeat text.",
                        )
                        appendLine("<source_fragment>")
                        appendLine(chunk)
                        appendLine("</source_fragment>")
                        appendLine("<partial_output>")
                        appendLine(output)
                        append("</partial_output>")
                    },
                    streaming = false,
                )
                output = ReformattedNoteDeduplicator.removeRepeatedContent(
                    "$output\n${cleanReformatOutput(continuation)}",
                )
            }

            val missing = AiGroundingValidator.missingFacts(chunk, output)
            val unsupported = AiGroundingValidator.unsupportedFacts(chunk, output)
            if (missing.isNotEmpty() || unsupported.isNotEmpty()) {
                output = repairReformatChunk(
                    runner = runner,
                    sessionId = "$taskId-$index-repair",
                    source = chunk,
                    candidate = output,
                    missing = missing,
                    unsupported = unsupported,
                    documentPlan = documentPlan,
                )
            }
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
        model: Model = QwenAdkModel(context.applicationContext),
        initialMemory: String = "",
        onMemoryUpdated: (String) -> Unit = {},
    ): NoteConversationAgent = NoteConversationAgent(
        model = model,
        sessionId = sessionId,
        noteContext = noteContext,
        systemInstruction = AiAgentPrompts.load(context).chatbot,
        webResearcher = AssistantWebLookup(context.applicationContext),
        initialMemory = initialMemory,
        onMemoryUpdated = onMemoryUpdated,
    )

    internal suspend fun auxiliaryTurn(
        model: Model,
        sessionId: String,
        systemInstruction: String,
        prompt: String,
        maxOutputTokens: Int,
    ): String {
        val agent = LlmAgent(
            name = AUXILIARY_AGENT_NAME,
            description = "Performs a constrained supporting step for the private note agent.",
            model = model,
            instruction = Instruction(systemInstruction),
            includeContents = LlmAgent.IncludeContents.NONE,
            generateContentConfig = GenerateContentConfig(
                temperature = 0.0f,
                topP = 0.8f,
                maxOutputTokens = maxOutputTokens,
            ),
            maxSteps = 1,
        )
        return InMemoryRunner(agent = agent, appName = APP_NAME).finalText(
            sessionId = sessionId,
            message = prompt,
            streaming = false,
        )
    }

    private suspend fun repairReformatChunk(
        runner: InMemoryRunner,
        sessionId: String,
        source: String,
        candidate: String,
        missing: Set<String>,
        unsupported: Set<String>,
        documentPlan: String,
    ): String {
        val repaired = runner.finalText(
            sessionId = sessionId,
            message = buildString {
                appendLine(
                    "Repair the candidate reformat. Restore every missing protected fact exactly, " +
                        "remove or correct unsupported facts, preserve the plan, and output Markdown only.",
                )
                appendLine("<document_plan>")
                appendLine(documentPlan)
                appendLine("</document_plan>")
                appendLine("<source_fragment>")
                appendLine(source)
                appendLine("</source_fragment>")
                appendLine("<candidate>")
                appendLine(candidate)
                appendLine("</candidate>")
                appendLine("<missing_protected_facts>")
                appendLine(missing.joinToString("\n"))
                appendLine("</missing_protected_facts>")
                appendLine("<unsupported_facts>")
                appendLine(unsupported.joinToString("\n"))
                append("</unsupported_facts>")
            },
            streaming = false,
        )
        return cleanReformatOutput(repaired)
    }

    private fun validateReformat(source: String, output: String): String {
        val cleaned = cleanReformatOutput(output)
        require(cleaned.isNotBlank()) { "The AI agent returned an empty reformat." }
        val sourceWords = source.wordCount()
        val outputWords = cleaned.wordCount()
        if (sourceWords >= 12) {
            require(outputWords >= ceil(sourceWords * MINIMUM_REFORMAT_WORD_RATIO).toInt()) {
                "The AI reformat omitted too much of the original note, so it was not applied."
            }
        }
        val missing = AiGroundingValidator.missingFacts(source, cleaned)
        require(missing.isEmpty()) {
            "The AI reformat omitted protected facts: ${missing.take(4).joinToString()}"
        }
        val unsupported = AiGroundingValidator.unsupportedFacts(source, cleaned)
        require(unsupported.isEmpty()) {
            "The AI reformat introduced unsupported facts: ${unsupported.take(4).joinToString()}"
        }
        return cleaned
    }

    private fun cleanReformatOutput(output: String): String =
        output.trim()
            .removeSurrounding("```markdown", "```")
            .removeSurrounding("```", "```")
            .trim()

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
    private const val REFORMAT_MAX_OUTPUT_TOKENS = 768
    private const val REFORMAT_MAX_INPUT_TOKENS = 1_100
    private const val OUTLINE_MAX_CHARS = 3_000
    private const val MAX_PLAN_CHARS = 900
    private const val MAX_USER_REFORMAT_INSTRUCTION_CHARS = 500
    private const val MAX_REFORMAT_RESEARCH_CHARS = 1_800
    private const val MINIMUM_REFORMAT_WORD_RATIO = 0.65
    private const val CONTINUATION_WORD_RATIO = 0.8
    private const val AUXILIARY_AGENT_NAME = "starbuck_note_auxiliary_agent"
}

internal class NoteConversationAgent(
    private val model: Model,
    private val sessionId: String,
    private val noteContext: String,
    private val systemInstruction: String,
    private val webResearcher: WebResearcher,
    initialMemory: String = "",
    private val onMemoryUpdated: (String) -> Unit = {},
) {
    private val sessionService = InMemorySessionService()
    private val recentTurns = ArrayDeque<ConversationTurn>()
    private var conversationMemory = initialMemory.trim()
    private val userPromptCharLimit =
        ((model as? QwenAdkModel)?.promptCharLimit ?: DEFAULT_MODEL_PROMPT_CHARS) -
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
        val answerRequest = AssistantWebLookup.resolveResearchQuery(
            message = trimmed,
            previousRequests = recentTurns.map(ConversationTurn::user),
        )
        val noteReference = NoteReference.parse(answerRequest)
        val researchPlan = if (model is QwenAdkModel) {
            emit(AgentTurnUpdate.Partial("Planning local and online evidence…"))
            runCatching {
                val raw = NoteAiAgent.auxiliaryTurn(
                    model = model,
                    sessionId = "$sessionId-plan-${recentTurns.size}",
                    systemInstruction =
                        "Return JSON only: {\"needs_web\":boolean,\"queries\":[string]," +
                            "\"freshness\":string,\"source_types\":[string]}. Use web evidence for " +
                            "current, unfamiliar, cited, linked, recommended, or explicitly researched " +
                            "facts. The current note is source evidence only when note_is_source is " +
                            "true. Otherwise use it only to derive a non-sensitive place, topic, or " +
                            "entity that makes each query self-contained. Never copy private details " +
                            "into a query. Prefer primary sources.",
                    prompt = buildString {
                        appendLine(
                            "<note_is_source>${noteReference.usesCurrentNoteAsSource}</note_is_source>",
                        )
                        appendLine("<current_note_context>")
                        appendLine(noteContext.take(RESEARCH_PLANNER_NOTE_CHARS))
                        appendLine("</current_note_context>")
                        append("<user_request>${answerRequest.take(600)}</user_request>")
                    },
                    maxOutputTokens = 96,
                )
                QwenResearchPlan.parse(
                    raw = raw,
                    question = noteReference.requestWithoutTag.ifBlank { answerRequest },
                    noteIsSource = noteReference.usesCurrentNoteAsSource,
                )
            }.getOrElse {
                QwenResearchPlan.fallback(
                    question = noteReference.requestWithoutTag.ifBlank { answerRequest },
                    noteIsSource = noteReference.usesCurrentNoteAsSource,
                )
            }
        } else {
            QwenResearchPlan.fallback(
                question = noteReference.requestWithoutTag.ifBlank { answerRequest },
                noteIsSource = noteReference.usesCurrentNoteAsSource,
            )
        }
        var research: WebLookupResult? = null
        if (researchPlan.needsWeb || AssistantWebLookup.requiresInternet(trimmed)) {
            emit(AgentTurnUpdate.Partial(AssistantWebLookup.RESEARCH_PROGRESS_MESSAGE))
            val queries = researchPlan.queries.ifEmpty {
                listOf(noteReference.requestWithoutTag.ifBlank { answerRequest })
            }
            val lookup = mergeWebLookupResults(
                queries.joinToString(" | "),
                queries.take(2).map { webResearcher.lookup(it) },
            )
            if (lookup.results.isNotEmpty()) {
                research = lookup
            } else if (AssistantWebLookup.requiresInternet(trimmed)) {
                val alert = AssistantWebLookup.INTERNET_REQUIRED_MESSAGE
                rememberTurn(trimmed, alert)
                emit(AgentTurnUpdate.Complete(alert))
                return@flow
            }
        }

        (model as? QwenAdkModel)?.requireAvailable()
        var finalText = generate(
            prompt = buildTurnPrompt(answerRequest, research),
            onPartial = { emit(AgentTurnUpdate.Partial(it)) },
        )

        // The local model gets the first opportunity for non-current questions. If it explicitly
        // says it cannot answer, research is attempted; an outage then produces a clear alert.
        if (research == null && AssistantWebLookup.answerNeedsResearch(finalText)) {
            emit(AgentTurnUpdate.Partial(AssistantWebLookup.RESEARCH_PROGRESS_MESSAGE))
            val lookup = webResearcher.lookup(
                noteReference.requestWithoutTag.ifBlank { answerRequest },
            )
            if (lookup.results.isEmpty()) {
                finalText = AssistantWebLookup.INTERNET_REQUIRED_MESSAGE
            } else {
                research = lookup
                finalText = generate(
                    prompt = buildTurnPrompt(answerRequest, lookup),
                    onPartial = { emit(AgentTurnUpdate.Partial(it)) },
                )
            }
        }

        // Extracted page evidence is still useful if the small local model incorrectly treats an
        // empty or unrelated note as a reason to refuse the request.
        require(finalText.isNotBlank()) { "The on-device agent returned no reply." }
        if (research != null &&
            AssistantWebLookup.answerNeedsResearch(finalText) &&
            model !is QwenAdkModel
        ) {
            // Test/custom providers do not have the Qwen verifier. Production QwenAdkModel turns
            // always use the Qwen verification pass below.
            finalText = AssistantWebLookup.quickAnswer(answerRequest, research)
        }
        finalText = verifyAnswer(answerRequest, finalText, research)
        if (research != null &&
            !AssistantWebLookup.answerSummarizesExtractedResearch(finalText, research)
        ) {
            finalText = AssistantWebLookup.quickAnswer(answerRequest, research)
        }
        research?.let { finalText = AssistantWebLookup.appendMarkdownSources(finalText, it) }
        updateMemory(answerRequest, finalText)
        rememberTurn(trimmed, finalText)
        emit(AgentTurnUpdate.Complete(finalText))
    }

    private fun buildTurnPrompt(message: String, research: WebLookupResult?): String =
        AgentContextPromptBuilder.build(
            currentNote = LocalNoteContextRetriever.retrieve(
                question = message,
                currentNote = Note(id = 0L, title = "Current note", content = noteContext),
                maxChars = userPromptCharLimit.coerceAtLeast(MIN_USER_PROMPT_CHARS),
            ),
            webResearch = research?.toPromptContext().orEmpty(),
            recentConversation = recentTurns.render(),
            conversationMemory = conversationMemory,
            userRequest = message,
            maxChars = userPromptCharLimit.coerceAtLeast(MIN_USER_PROMPT_CHARS),
        )

    private suspend fun verifyAnswer(
        question: String,
        draft: String,
        research: WebLookupResult?,
    ): String {
        if (model !is QwenAdkModel) return draft
        val noteReference = NoteReference.parse(question)
        return runCatching {
            NoteAiAgent.auxiliaryTurn(
                model = model,
                sessionId = "$sessionId-verify-${recentTurns.size}",
                systemInstruction =
                    "Verify the draft using only supplied evidence. Correct unsupported claims and " +
                        "citations. Output only the corrected answer.",
                prompt = buildString {
                    appendLine("<question>${question.take(600)}</question>")
                    if (noteReference.usesCurrentNoteAsSource) {
                        appendLine("<local_evidence>${noteContext.take(1_600)}</local_evidence>")
                    } else {
                        appendLine("<note_context_not_evidence>true</note_context_not_evidence>")
                    }
                    research?.let {
                        appendLine("<web_evidence>")
                        appendLine(it.toPromptContext().take(2_400))
                        appendLine("</web_evidence>")
                    }
                    appendLine("<draft>${draft.take(1_600)}</draft>")
                },
                maxOutputTokens = 320,
            )
        }.getOrDefault(draft).ifBlank { draft }
    }

    private suspend fun updateMemory(question: String, answer: String) {
        if (model !is QwenAdkModel) return
        val updated = runCatching {
            NoteAiAgent.auxiliaryTurn(
                model = model,
                sessionId = "$sessionId-memory-${recentTurns.size}",
                systemInstruction =
                    "Maintain compact note-chat memory. Keep durable preferences, named entities, " +
                        "decisions, constraints, and unresolved questions with User/Note/Web " +
                        "provenance. Use short bullets under 180 words. Output memory only.",
                prompt = buildString {
                    appendLine("<previous_memory>${conversationMemory.take(1_200)}</previous_memory>")
                    appendLine("<user>${question.take(600)}</user>")
                    appendLine("<assistant>${answer.take(1_200)}</assistant>")
                },
                maxOutputTokens = 240,
            )
        }.getOrDefault(conversationMemory)
        if (updated.isNotBlank()) {
            conversationMemory = updated.take(1_200)
            onMemoryUpdated(conversationMemory)
        }
    }

    private suspend fun generate(
        prompt: String,
        onPartial: suspend (String) -> Unit,
    ): String {
        val accumulated = StringBuilder()
        var finalText = ""
        withTimeout(NoteAiAgent.AGENT_TURN_TIMEOUT_MS) {
            runner.runAsync(
                userId = "local-note-user",
                sessionId = sessionId,
                newMessage = Content(role = Role.USER, parts = listOf(Part(text = prompt))),
                runConfig = RunConfig(streamingMode = StreamingMode.SSE),
            ).collect { event ->
                event.errorMessage?.takeIf { it.isNotBlank() }?.let { error(it) }
                if (event.author != NoteAiAgent.AGENT_NAME) return@collect
                val text = event.content?.parts?.mapNotNull { it.text }?.joinToString("").orEmpty()
                if (event.partial) {
                    accumulated.append(text)
                    onPartial(accumulated.toString())
                } else {
                    finalText = text.ifBlank { accumulated.toString() }.trim()
                }
            }
        }
        return finalText
    }

    private fun rememberTurn(user: String, assistant: String) {
        recentTurns += ConversationTurn(user = user, assistant = assistant)
        while (recentTurns.size > MAX_RECENT_EXCHANGES) recentTurns.removeFirst()
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
        const val RESEARCH_PLANNER_NOTE_CHARS = 1_200
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
    fun chunkForQwen(text: String, maxChars: Int, maxTokens: Int): List<String> {
        val tokenLimit = maxTokens.coerceAtLeast(MIN_QWEN_CHUNK_TOKENS)
        return chunk(text, maxChars).flatMap { candidate ->
            val estimated = QwenTokenBudget.estimateTokens(candidate)
            if (estimated <= tokenLimit) {
                listOf(candidate)
            } else {
                val scaledChars = (candidate.length.toLong() * tokenLimit / estimated)
                    .toInt()
                    .coerceIn(MIN_CHUNK_CHARS, maxChars)
                chunk(candidate, scaledChars)
            }
        }
    }

    fun outlineSource(text: String, maxChars: Int): String {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.length <= maxChars) return normalized
        val structuralLines = normalized.lines()
            .filter { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("#") ||
                    trimmed.matches(Regex("""(?:[-*+]|\d+[.)]|\[[ xX]])\s+.*"""))
            }
            .joinToString("\n")
            .take(maxChars / 3)
        val remaining = (maxChars - structuralLines.length - OMISSION.length).coerceAtLeast(0)
        val head = normalized.take(remaining / 2)
        val tail = normalized.takeLast(remaining - head.length)
        return listOf(head, structuralLines, OMISSION, tail)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .take(maxChars)
    }

    fun chunk(text: String, maxChars: Int): List<String> {
        require(maxChars >= MIN_CHUNK_CHARS)
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

    private const val MIN_CHUNK_CHARS = 400
    private const val MIN_QWEN_CHUNK_TOKENS = 160
    private const val OMISSION = "[...middle content represented by structural lines...]"
}
