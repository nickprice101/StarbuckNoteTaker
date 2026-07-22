package com.example.starbucknotetaker

import android.content.Context

internal data class AiAgentPromptSet(
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
            chatbot = requiredSection(CHATBOT_SECTION),
            reformatting = requiredSection(REFORMATTING_SECTION),
        )
    }

    private const val CHATBOT_SECTION = "AI_CHATBOT"
    private const val REFORMATTING_SECTION = "AI_REFORMATTING"
}

/** Builds explicit, bounded context blocks so the current note cannot be mistaken for chat history. */
internal object AgentContextPromptBuilder {
    private const val OMISSION_MARKER = "\n[...context omitted to fit the on-device model...]\n"

    fun build(
        currentNote: String,
        userRequest: String,
        recentConversation: String = "",
        webResearch: String = "",
        maxChars: Int,
    ): String {
        require(maxChars >= 320) { "Agent context budget is too small" }
        val includeRecentConversation = recentConversation.isNotBlank()
        val includeWebResearch = webResearch.isNotBlank()
        val structuralChars = baseStructuralChars + if (includeRecentConversation) {
            recentConversationStructuralChars
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
        val note = compact(currentNote.trim(), remaining)

        return buildString {
            appendLine(CURRENT_NOTE_OPEN)
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
    private const val MAX_WEB_RESEARCH_CHARS = 2_400
    private const val CURRENT_NOTE_OPEN = "<current_note>"
    private const val CURRENT_NOTE_CLOSE = "</current_note>"
    private const val WEB_RESEARCH_OPEN = "<web_research>"
    private const val WEB_RESEARCH_CLOSE = "</web_research>"
    private const val RECENT_CONVERSATION_OPEN = "<recent_conversation>"
    private const val RECENT_CONVERSATION_CLOSE = "</recent_conversation>"
    private const val USER_REQUEST_OPEN = "<user_request>"
    private const val USER_REQUEST_CLOSE = "</user_request>"
    private val baseStructuralChars = CURRENT_NOTE_OPEN.length +
        CURRENT_NOTE_CLOSE.length + USER_REQUEST_OPEN.length + USER_REQUEST_CLOSE.length + 6
    private val recentConversationStructuralChars = RECENT_CONVERSATION_OPEN.length +
        RECENT_CONVERSATION_CLOSE.length + 4
    private val webResearchStructuralChars = WEB_RESEARCH_OPEN.length +
        WEB_RESEARCH_CLOSE.length + 4
}
