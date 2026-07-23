package com.example.starbucknotetaker

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared support code for the on-device Qwen workflows.
 *
 * Qwen owns every semantic decision and generated result. The helpers in this file only protect
 * source data, select bounded context, parse constrained responses, cache completed output, and
 * reject facts that the model cannot ground in the supplied evidence.
 */
internal object QwenTokenBudget {
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        val words = WORD_REGEX.findAll(text).count()
        val punctuation = PUNCTUATION_REGEX.findAll(text).count()
        val nonAscii = text.count { it.code > 0x7f }
        return maxOf(1, ceil(words * 1.28 + punctuation * 0.22 + nonAscii * 0.45).toInt())
    }

    fun charsForTokens(tokens: Int): Int = (tokens.coerceAtLeast(1) * AVERAGE_CHARS_PER_TOKEN)

    private const val AVERAGE_CHARS_PER_TOKEN = 4
    private val WORD_REGEX = Regex("""[\p{L}\p{N}_'-]+""")
    private val PUNCTUATION_REGEX = Regex("""[^\p{L}\p{N}\s]""")
}

/** Selects locally stored note fragments that are most relevant to a Qwen question-answer turn. */
internal object LocalNoteContextRetriever {
    fun retrieve(
        question: String,
        currentNote: Note,
        notes: List<Note>,
        canRead: (Note) -> Boolean,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        val queryTerms = importantTerms(question)
        val candidates = buildList {
            addAll(noteChunks(currentNote, isCurrent = true))
            notes.asSequence()
                .filter { it.id != currentNote.id && canRead(it) }
                .sortedByDescending(Note::date)
                .take(MAX_RELATED_NOTES)
                .flatMap { noteChunks(it, isCurrent = false).asSequence() }
                .forEach(::add)
        }.map { candidate ->
            candidate.copy(score = relevanceScore(candidate, queryTerms))
        }

        val selected = mutableListOf<ContextChunk>()
        candidates.firstOrNull { it.isCurrent }?.let(selected::add)
        candidates.asSequence()
            .filterNot { it in selected }
            .sortedWith(
                compareByDescending<ContextChunk> { it.score }
                    .thenByDescending { it.isCurrent }
                    .thenByDescending(ContextChunk::date),
            )
            .forEach { candidate ->
                if (selected.none { it.noteId == candidate.noteId && it.text == candidate.text }) {
                    selected += candidate
                }
            }

        val output = StringBuilder()
        selected.forEach { chunk ->
            val block = buildString {
                appendLine(
                    if (chunk.isCurrent) {
                        "<local_note role=\"current\" title=\"${chunk.title.xmlEscape()}\">"
                    } else {
                        "<local_note role=\"related\" title=\"${chunk.title.xmlEscape()}\">"
                    },
                )
                appendLine(chunk.text)
                append("</local_note>")
            }
            if (output.isNotEmpty() && output.length + block.length + 2 > maxChars) return@forEach
            if (output.isNotEmpty()) output.appendLine().appendLine()
            output.append(block.take((maxChars - output.length).coerceAtLeast(0)))
        }
        return output.toString().trim()
    }

    private fun noteChunks(note: Note, isCurrent: Boolean): List<ContextChunk> {
        val structured = buildString {
            if (note.checklistItems != null) {
                append(note.checklistItems.asChecklistContent())
            } else {
                append(note.content)
            }
            note.event?.let { event ->
                appendLine()
                appendLine()
                append("Event: ${event.start}-${event.end}; timezone=${event.timeZone}")
                event.location?.takeIf(String::isNotBlank)?.let { append("; location=$it") }
            }
        }.trim()
        val paragraphs = structured.split(Regex("""\n{2,}"""))
            .flatMap { paragraph ->
                if (paragraph.length <= CHUNK_CHARS) listOf(paragraph)
                else paragraph.chunked(CHUNK_CHARS)
            }
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty { listOf(note.title) }

        return paragraphs.mapIndexed { index, paragraph ->
            ContextChunk(
                noteId = note.id,
                title = note.title,
                text = paragraph,
                isCurrent = isCurrent,
                date = note.date,
                position = index,
            )
        }
    }

    private fun relevanceScore(chunk: ContextChunk, queryTerms: Set<String>): Int {
        val titleTerms = importantTerms(chunk.title)
        val bodyTerms = importantTerms(chunk.text)
        val titleMatches = queryTerms.count(titleTerms::contains)
        val bodyMatches = queryTerms.count(bodyTerms::contains)
        return titleMatches * 8 + bodyMatches * 3 +
            if (chunk.isCurrent) CURRENT_NOTE_BONUS else 0 -
            chunk.position.coerceAtMost(4)
    }

    private fun importantTerms(text: String): Set<String> =
        TERM_REGEX.findAll(text.lowercase(Locale.US))
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

    private data class ContextChunk(
        val noteId: Long,
        val title: String,
        val text: String,
        val isCurrent: Boolean,
        val date: Long,
        val position: Int,
        val score: Int = 0,
    )

    private const val DEFAULT_MAX_CHARS = 3_000
    private const val CHUNK_CHARS = 700
    private const val MAX_RELATED_NOTES = 24
    private const val CURRENT_NOTE_BONUS = 10
    private val TERM_REGEX = Regex("""[\p{L}\p{N}][\p{L}\p{N}_'-]*""")
    private val STOP_WORDS = setOf(
        "and", "are", "but", "can", "did", "for", "from", "have", "how", "into", "not",
        "note", "that", "the", "their", "then", "this", "was", "were", "what", "when",
        "where", "which", "with", "would", "you", "your",
    )
}

/** Persistent memory for non-encrypted notes; locked-note turns remain process-local only. */
internal class ConversationMemoryStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(noteId: Long, persistent: Boolean): String =
        if (persistent) {
            preferences.getString(key(noteId), null).orEmpty()
        } else {
            synchronized(volatileMemory) { volatileMemory[noteId].orEmpty() }
        }

    @Synchronized
    fun put(noteId: Long, memory: String, persistent: Boolean) {
        val bounded = memory.trim().take(MAX_MEMORY_CHARS)
        if (persistent) {
            preferences.edit().putString(key(noteId), bounded).apply()
        } else {
            synchronized(volatileMemory) {
                if (bounded.isBlank()) volatileMemory.remove(noteId) else volatileMemory[noteId] = bounded
            }
        }
    }

    private fun key(noteId: Long): String = "note_$noteId"

    private companion object {
        const val PREFERENCES_NAME = "qwen_conversation_memory"
        const val MAX_MEMORY_CHARS = 1_200
        val volatileMemory = mutableMapOf<Long, String>()
    }
}

internal data class QwenResearchPlan(
    val needsWeb: Boolean,
    val queries: List<String>,
    val freshness: String,
    val sourceTypes: List<String>,
) {
    companion object {
        fun parse(raw: String, question: String): QwenResearchPlan {
            val cleaned = raw.extractJsonObject()
            return runCatching {
                val json = JSONObject(cleaned)
                QwenResearchPlan(
                    needsWeb = json.optBoolean("needs_web", false),
                    queries = json.optJSONArray("queries").stringValues()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct()
                        .take(MAX_RESEARCH_QUERIES),
                    freshness = json.optString("freshness", "stable").ifBlank { "stable" },
                    sourceTypes = json.optJSONArray("source_types").stringValues()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .take(MAX_SOURCE_TYPES),
                )
            }.getOrElse {
                fallback(question)
            }
        }

        fun fallback(question: String): QwenResearchPlan = QwenResearchPlan(
            needsWeb = AssistantWebLookup.shouldLookup(question),
            queries = listOf(question.trim()).filter(String::isNotBlank),
            freshness = if (AssistantWebLookup.requiresInternet(question)) "current" else "stable",
            sourceTypes = emptyList(),
        )

        private const val MAX_RESEARCH_QUERIES = 2
        private const val MAX_SOURCE_TYPES = 3
    }
}

/** Parses Qwen's category-aware summary schema into the two-line main-page representation. */
internal object QwenSummaryProtocol {
    val systemPrompt = """
        You are the on-device summary agent for a notes app. Infer the note type and return exactly
        one JSON object with these string fields: type, gist, action, deadline, key_detail.
        Allowed types: FOOD_RECIPE, PERSONAL_DAILY_LIFE, FINANCE_LEGAL, SELF_IMPROVEMENT,
        HEALTH_WELLNESS, EDUCATION_LEARNING, HOME_FAMILY, WORK_PROJECT, MEETING_RECAP,
        SHOPPING_LIST, GENERAL_CHECKLIST, REMINDER, TRAVEL_LOG, CREATIVE_WRITING,
        TECHNICAL_REFERENCE. Use an empty string for fields that do not apply. Preserve names,
        dates, amounts, measurements, decisions, and actions exactly. Do not invent facts. Make gist
        a concrete, category-aware enhanced summary, not a generic label. The rendered result must
        fit in two short lines. Output JSON only.
    """.trimIndent()

    fun render(raw: String): String {
        val json = runCatching { JSONObject(raw.extractJsonObject()) }.getOrNull()
            ?: return cleanPlainSummary(raw)
        val gist = json.optString("gist").cleanSummaryField()
        val action = json.optString("action").cleanSummaryField()
        val deadline = json.optString("deadline").cleanSummaryField()
        val detail = json.optString("key_detail").cleanSummaryField()
        val first = gist.ifBlank { detail }.ifBlank { action }.ifBlank { deadline }
        val second = listOfNotNull(
            action.takeIf { it.isNotBlank() && it != first }?.let { "Action: $it" },
            deadline.takeIf { it.isNotBlank() && it != first }?.let { "Due: $it" },
            detail.takeIf { it.isNotBlank() && it != first }?.let { "Key detail: $it" },
        ).firstOrNull().orEmpty()
        return listOf(first, second)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .let { Summarizer.smartTruncate(it, MAX_RENDERED_CHARS) }
    }

    private fun cleanPlainSummary(raw: String): String =
        raw.removeSurrounding("```json", "```")
            .removeSurrounding("```", "```")
            .trim()
            .takeUnless { it.startsWith("{") || it.endsWith("}") }
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .take(2)
            .joinToString("\n")
            .let { Summarizer.smartTruncate(it, MAX_RENDERED_CHARS) }

    private fun String.cleanSummaryField(): String =
        replace(Regex("""\s+"""), " ").trim().trimEnd('.', ';')

    private const val MAX_RENDERED_CHARS = 190
}

/**
 * High-risk fact checks shared by summaries, answers, and reformats.
 *
 * The validator is deliberately conservative: ordinary prose may change, but URLs, numbers,
 * clock times, email addresses, code spans, and capitalized multi-word names may not appear from
 * nowhere. Reformatting additionally requires every protected source fact to survive.
 */
internal object AiGroundingValidator {
    fun unsupportedFacts(source: String, output: String): Set<String> {
        val sourceFacts = protectedFacts(source)
        return protectedFacts(output).filterNot { fact ->
            sourceFacts.any { sourceFact -> sourceFact.equals(fact, ignoreCase = true) }
        }.toSet()
    }

    fun missingFacts(source: String, output: String): Set<String> {
        val outputFacts = protectedFacts(output)
        return protectedFacts(source).filterNot { fact ->
            outputFacts.any { outputFact -> outputFact.equals(fact, ignoreCase = true) }
        }.toSet()
    }

    fun protectedFacts(text: String): Set<String> = buildSet {
        FACT_PATTERNS.forEach { regex ->
            regex.findAll(text).map { it.value.trim() }.filter(String::isNotBlank).forEach(::add)
        }
    }

    fun likelyTruncated(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return true
        if (trimmed.count { it == '`' } % 2 != 0) return true
        if (trimmed.count { it == '[' } != trimmed.count { it == ']' }) return true
        return trimmed.last() !in ".!?):]}`\"'"
    }

    private val FACT_PATTERNS = listOf(
        Regex("""https://[^\s)>\]]+""", RegexOption.IGNORE_CASE),
        Regex("""\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b"""),
        Regex("""(?<!\w)(?:[$€£]\s*)?\d[\d,.]*(?:\s*(?:%|am|pm|kg|g|lb|lbs|km|miles?|minutes?|hours?|GB|MB|mg|ml|°[CF]))?(?!\w)""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)(?:day)?\b|\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2}(?:,\s+\d{4})?\b""", RegexOption.IGNORE_CASE),
        Regex("""```[\s\S]*?```"""),
        Regex("""`[^`\n]+`"""),
        Regex("""\[\[(?:image|file):\d+]]""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:Mr|Mrs|Ms|Dr|Prof)\.?\s+[A-Z][\p{L}'-]+\b"""),
        Regex("""(?<!\w)@[A-Za-z0-9_]{2,}"""),
    )
}

/**
 * Process-local content-addressed cache for completed Qwen summaries.
 *
 * Summary text is intentionally not written to a separate preferences file because it may belong
 * to an encrypted note. The encrypted note store remains the only persistent owner of that text.
 */
internal class QwenSummaryCache(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun get(source: String): String? = synchronized(entries) {
        entries[source.sha256()]?.summary
    }

    fun put(source: String, summary: String) {
        if (summary.isBlank()) return
        synchronized(entries) {
            entries[source.sha256()] = SummaryCacheEntry(
                summary = summary.trim(),
                savedAt = System.currentTimeMillis(),
            )
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.minByOrNull { it.value.savedAt }?.key ?: break
                entries.remove(oldest)
            }
        }
    }

    private data class SummaryCacheEntry(val summary: String, val savedAt: Long)

    private companion object {
        const val MAX_ENTRIES = 96
        val entries = linkedMapOf<String, SummaryCacheEntry>()
    }
}

/** Describes the original document to Qwen without allowing protected rich-text elements to drift. */
internal object StructuredNoteDocument {
    fun describe(markdown: String): String = buildString {
        markdown.replace("\r\n", "\n").lines().forEachIndexed { index, line ->
            val kind = when {
                line.trimStart().startsWith("```") -> "code_fence"
                line.trimStart().startsWith("#") -> "heading"
                line.contains(Regex("""\[\[(?:image|file):\d+]]""")) -> "attachment"
                line.contains(Regex("""https://|]\(https://""")) -> "linked_text"
                line.matches(Regex("""\s*(?:[-*+]|\d+[.)]|\[[ xX]])\s+.*""")) -> "list_item"
                line.isBlank() -> "separator"
                else -> "paragraph"
            }
            append("<block index=\"")
            append(index)
            append("\" kind=\"")
            append(kind)
            append("\">")
            append(line)
            appendLine("</block>")
        }
    }.trim()
}

internal fun mergeWebLookupResults(
    query: String,
    results: List<WebLookupResult>,
): WebLookupResult {
    val entries = results.flatMap(WebLookupResult::results).distinctBy(WebLookupEntry::url).take(6)
    val error = results.mapNotNull(WebLookupResult::error).firstOrNull()
    val errorKind = if (entries.isEmpty()) results.mapNotNull(WebLookupResult::errorKind).firstOrNull() else null
    return WebLookupResult(query = query, results = entries, error = error, errorKind = errorKind)
}

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

private fun String.extractJsonObject(): String {
    val cleaned = removeSurrounding("```json", "```")
        .removeSurrounding("```", "```")
        .trim()
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private fun String.xmlEscape(): String =
    replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
