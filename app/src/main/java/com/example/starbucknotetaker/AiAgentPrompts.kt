package com.example.starbucknotetaker

import android.content.Context

internal data class AiAgentPromptSet(
    val summariser: String,
    val chatbot: String,
    val reformatting: String,
)

/** Loads the editable agent prompts that the Android build packages as an app asset. */
internal object AiAgentPrompts {
    const val ASSET_NAME = "AI_AGENT_PROMPTS.txt"

    fun load(context: Context): AiAgentPromptSet = context.assets.open(ASSET_NAME)
        .bufferedReader()
        .use { reader -> parse(reader.readText()) }

    internal fun parse(source: String): AiAgentPromptSet {
        val sections = mutableMapOf<String, StringBuilder>()
        var activeSection: String? = null

        source.lineSequence().forEach { line ->
            val section = when (line.trim()) {
                "[$SUMMARISER_SECTION]" -> SUMMARISER_SECTION
                "[$CHATBOT_SECTION]" -> CHATBOT_SECTION
                "[$REFORMATTING_SECTION]" -> REFORMATTING_SECTION
                else -> null
            }
            if (section != null) {
                activeSection = section
                sections.getOrPut(section) { StringBuilder() }
            } else {
                activeSection?.let { name -> sections.getValue(name).appendLine(line) }
            }
        }

        fun requiredSection(name: String): String = sections[name]
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error("$ASSET_NAME is missing the required [$name] section")

        return AiAgentPromptSet(
            summariser = requiredSection(SUMMARISER_SECTION),
            chatbot = requiredSection(CHATBOT_SECTION),
            reformatting = requiredSection(REFORMATTING_SECTION),
        )
    }

    private const val SUMMARISER_SECTION = "AI_SUMMARISER"
    private const val CHATBOT_SECTION = "AI_CHATBOT"
    private const val REFORMATTING_SECTION = "AI_REFORMATTING"
}

internal data class NoteReference(
    val usesCurrentNoteAsSource: Boolean,
    val requestWithoutTag: String,
) {
    companion object {
        private val noteTag = Regex("""(?i)(?<![\p{L}\p{N}_])/note\b""")

        fun parse(request: String): NoteReference {
            val usesCurrentNoteAsSource = noteTag.containsMatchIn(request)
            val requestWithoutTag = noteTag.replace(request, " ")
                .replace(Regex("""[ \t]{2,}"""), " ")
                .replace(Regex("""[ \t]+([,.;:!?])"""), "$1")
                .trim()
            return NoteReference(
                usesCurrentNoteAsSource = usesCurrentNoteAsSource,
                requestWithoutTag = requestWithoutTag,
            )
        }
    }
}

/** Builds explicit, bounded context blocks so the one current note has an unambiguous role. */
internal object AgentContextPromptBuilder {
    private const val OMISSION_MARKER = "\n[...context omitted to fit the on-device model...]\n"

    fun build(
        currentNote: String,
        userRequest: String,
        recentConversation: String = "",
        conversationMemory: String = "",
        webResearch: String = "",
        maxChars: Int,
    ): String {
        require(maxChars >= 320) { "Agent context budget is too small" }
        val includeRecentConversation = recentConversation.isNotBlank()
        val includeConversationMemory = conversationMemory.isNotBlank()
        val includeWebResearch = webResearch.isNotBlank()
        val structuralChars = baseStructuralChars + if (includeRecentConversation) {
            recentConversationStructuralChars
        } else {
            0
        } + if (includeConversationMemory) {
            conversationMemoryStructuralChars
        } else {
            0
        } + if (includeWebResearch) webResearchStructuralChars else 0
        var remaining = (maxChars - structuralChars).coerceAtLeast(0)
        val requestBudget = minOf(MAX_USER_REQUEST_CHARS, remaining * 2 / 5)
        val request = compact(userRequest.trim(), requestBudget)
        remaining -= request.length

        val researchBudget = if (!includeWebResearch) {
            0
        } else {
            minOf(MAX_WEB_RESEARCH_CHARS, remaining * 2 / 3)
        }
        val research = compact(webResearch.trim(), researchBudget)
        remaining -= research.length

        val recentBudget = if (!includeRecentConversation) {
            0
        } else {
            minOf(MAX_RECENT_CONVERSATION_CHARS, remaining / 3)
        }
        val recent = compact(recentConversation.trim(), recentBudget)
        remaining -= recent.length

        val memoryBudget = if (!includeConversationMemory) {
            0
        } else {
            minOf(MAX_CONVERSATION_MEMORY_CHARS, remaining / 2)
        }
        val memory = compact(conversationMemory.trim(), memoryBudget)
        remaining -= memory.length
        val note = compact(currentNote.trim(), remaining)
        val noteUsage = if (NoteReference.parse(userRequest).usesCurrentNoteAsSource) {
            NOTE_USAGE_SOURCE
        } else {
            NOTE_USAGE_CONTEXT_ONLY
        }

        return buildString {
            appendLine("<current_note usage=\"$noteUsage\">")
            appendLine(note)
            appendLine(CURRENT_NOTE_CLOSE)
            if (research.isNotBlank()) {
                appendLine()
                appendLine(WEB_RESEARCH_OPEN)
                appendLine(research)
                appendLine(WEB_RESEARCH_CLOSE)
            }
            if (recent.isNotBlank()) {
                appendLine()
                appendLine(RECENT_CONVERSATION_OPEN)
                appendLine(recent)
                appendLine(RECENT_CONVERSATION_CLOSE)
            }
            if (memory.isNotBlank()) {
                appendLine()
                appendLine(CONVERSATION_MEMORY_OPEN)
                appendLine(memory)
                appendLine(CONVERSATION_MEMORY_CLOSE)
            }
            appendLine()
            appendLine(USER_REQUEST_OPEN)
            appendLine(request)
            append(USER_REQUEST_CLOSE)
        }.let { prompt ->
            check(prompt.length <= maxChars) {
                "Structured agent context exceeded its $maxChars character budget"
            }
            prompt
        }
    }

    private fun compact(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.isBlank()) return ""
        if (text.length <= maxChars) return text
        if (maxChars <= OMISSION_MARKER.length + 2) return text.take(maxChars)
        val available = maxChars - OMISSION_MARKER.length
        val headChars = available * 2 / 5
        val tailChars = available - headChars
        return text.take(headChars) + OMISSION_MARKER + text.takeLast(tailChars)
    }

    private const val MAX_USER_REQUEST_CHARS = 600
    private const val MAX_RECENT_CONVERSATION_CHARS = 800
    private const val MAX_CONVERSATION_MEMORY_CHARS = 1_000
    private const val MAX_WEB_RESEARCH_CHARS = 2_400
    private const val CURRENT_NOTE_CLOSE = "</current_note>"
    private const val NOTE_USAGE_CONTEXT_ONLY = "context_only"
    private const val NOTE_USAGE_SOURCE = "source"
    private const val WEB_RESEARCH_OPEN = "<web_research>"
    private const val WEB_RESEARCH_CLOSE = "</web_research>"
    private const val RECENT_CONVERSATION_OPEN = "<recent_conversation>"
    private const val RECENT_CONVERSATION_CLOSE = "</recent_conversation>"
    private const val CONVERSATION_MEMORY_OPEN = "<conversation_memory>"
    private const val CONVERSATION_MEMORY_CLOSE = "</conversation_memory>"
    private const val USER_REQUEST_OPEN = "<user_request>"
    private const val USER_REQUEST_CLOSE = "</user_request>"
    private val baseStructuralChars = "<current_note usage=\"$NOTE_USAGE_CONTEXT_ONLY\">".length +
        CURRENT_NOTE_CLOSE.length + USER_REQUEST_OPEN.length + USER_REQUEST_CLOSE.length + 6
    private val recentConversationStructuralChars = RECENT_CONVERSATION_OPEN.length +
        RECENT_CONVERSATION_CLOSE.length + 4
    private val conversationMemoryStructuralChars = CONVERSATION_MEMORY_OPEN.length +
        CONVERSATION_MEMORY_CLOSE.length + 4
    private val webResearchStructuralChars = WEB_RESEARCH_OPEN.length +
        WEB_RESEARCH_CLOSE.length + 4
}
