package com.example.starbucknotetaker

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/**
 * Provides lightweight note intent classification for the app. The taxonomy is intentionally
 * narrow and tailored to the primary note-taking scenarios we support:
 *
 * 1. [NoteNatureType.MEETING_RECAP] &ndash; agenda summaries, minutes, action items.
 * 2. [NoteNatureType.SHOPPING_LIST] &ndash; shopping or packing checklists.
 * 3. [NoteNatureType.REMINDER] &ndash; short, time-sensitive prompts or follow ups.
 * 4. [NoteNatureType.JOURNAL_ENTRY] &ndash; reflective journal or diary style notes.
 * 5. [NoteNatureType.TRAVEL_PLAN] &ndash; itineraries, reservations, and logistics.
 * 6. [NoteNatureType.GENERAL_NOTE] &ndash; catch-all fallback when confidence is low.
 *
 * The classifier relies on language-agnostic normalization (lowercasing, accent removal, stop-word
 * filtering) and a collection of deterministic heuristics. Each category is scored via keyword
 * matches, structural cues (such as bullet lists), and optional calendar event metadata. When no
 * category achieves a reasonable score the classifier returns a general-purpose label.
 */
open class NoteNatureClassifier {

    open suspend fun classify(text: String, event: NoteEvent?): NoteNatureLabel {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return FALLBACK_LABEL
        }

        val tokens = normalizeTokens(trimmed)
        if (tokens.isEmpty()) {
            return FALLBACK_LABEL
        }

        val tokenFrequency = tokens.groupingBy { it }.eachCount()
        val context = NormalizedContext(
            originalText = trimmed,
            tokens = tokens,
            tokenFrequency = tokenFrequency,
            joined = tokens.joinToString(separator = " "),
            lines = trimmed.lines()
        )

        val scores = categoryDefinitions.associate { definition ->
            val score = definition.score(context, event)
            definition.type to score
        }

        val best = scores.maxByOrNull { it.value }
        if (best == null) {
            return FALLBACK_LABEL
        }

        val bestScore = best.value
        val tokenCount = context.tokens.size.coerceAtLeast(1)
        val relativeScore = bestScore / tokenCount
        if (bestScore < MIN_ABSOLUTE_SCORE || relativeScore < MIN_RELATIVE_SCORE) {
            return FALLBACK_LABEL
        }

        val type = best.key
        val confidence = relativeScore.coerceIn(0.0, 1.0)
        return NoteNatureLabel(type, type.humanReadable, confidence)
    }

    private fun normalizeTokens(text: String): List<String> {
        val withoutDiacritics = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(ACCENT_REGEX, "")
        val cleaned = withoutDiacritics
            .replace("'", "")
            .lowercase(Locale.ROOT)
        val rawTokens = TOKEN_SPLIT_REGEX.split(cleaned)
        return rawTokens
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .filterNot { stopWords.contains(it) }
    }

    private data class NormalizedContext(
        val originalText: String,
        val tokens: List<String>,
        val tokenFrequency: Map<String, Int>,
        val joined: String,
        val lines: List<String>
    ) {
        val uniqueTokens: Set<String> = tokenFrequency.keys
    }

    private data class CategoryDefinition(
        val type: NoteNatureType,
        val keywordWeights: Map<String, Double>,
        val phraseWeights: Map<String, Double> = emptyMap(),
        val structuralBonus: (NormalizedContext) -> Double = { 0.0 },
        val eventBonus: (NoteEvent?, NormalizedContext) -> Double = { _, _ -> 0.0 }
    ) {
        fun score(context: NormalizedContext, event: NoteEvent?): Double {
            var total = 0.0
            for ((keyword, weight) in keywordWeights) {
                val matches = context.tokenFrequency[keyword] ?: 0
                if (matches > 0) {
                    total += matches * weight
                }
            }
            for ((phrase, weight) in phraseWeights) {
                if (context.joined.contains(phrase)) {
                    total += weight
                }
            }
            total += structuralBonus(context)
            total += eventBonus(event, context)
            return total
        }
    }

    companion object {
        private val FALLBACK_LABEL = NoteNatureLabel(
            NoteNatureType.GENERAL_NOTE,
            NoteNatureType.GENERAL_NOTE.humanReadable,
            0.0
        )

        private const val MIN_ABSOLUTE_SCORE = 2.0
        private const val MIN_RELATIVE_SCORE = 0.25

        private val TOKEN_SPLIT_REGEX = "\\W+".toRegex()
        private val ACCENT_REGEX = "\\p{Mn}+".toRegex()

        private val stopWords = setOf(
            // English
            "the", "and", "or", "but", "if", "a", "an", "of", "for", "on", "in", "at", "to", "with", "from",
            "by", "about", "into", "over", "after", "before", "again", "further", "then", "once", "this",
            "that", "these", "those", "is", "are", "was", "were", "be", "been", "being", "have", "has",
            "had", "do", "does", "did", "dont", "doesnt", "cant", "im", "its",
            // Spanish
            "el", "la", "los", "las", "de", "del", "y", "a", "un", "una", "para", "con", "sin", "como",
            "pero", "porque", "cuando", "donde",
            // French
            "le", "la", "les", "des", "du", "et", "ou", "un", "une", "pour", "sans", "avec",
            // Portuguese / Italian
            "um", "uma", "dos", "das", "nos", "nas", "por", "para", "come", "per",
            // German
            "der", "die", "das", "und", "oder", "ein", "eine", "mit", "von"
        )

        private val categoryDefinitions = listOf(
            CategoryDefinition(
                type = NoteNatureType.MEETING_RECAP,
                keywordWeights = mapOf(
                    "meeting" to 3.0,
                    "recap" to 2.5,
                    "minutes" to 2.0,
                    "attendees" to 2.0,
                    "agenda" to 2.0,
                    "discussion" to 1.5,
                    "action" to 1.0,
                    "items" to 1.0,
                    "follow" to 0.5,
                    "up" to 0.5,
                    "next" to 0.5,
                    "steps" to 1.5,
                    "decision" to 1.5,
                    "summary" to 1.0
                ),
                phraseWeights = mapOf(
                    "action items" to 2.5,
                    "follow up" to 1.5,
                    "next steps" to 1.5
                ),
                structuralBonus = { context ->
                    val bulletLines = context.lines.count { line ->
                        val trimmed = line.trimStart()
                        trimmed.startsWith("-") || trimmed.startsWith("*") || trimmed.startsWith("•") || trimmed.matches(Regex("\\d+\\. .*"))
                    }
                    if (bulletLines >= 2) 1.5 else 0.0
                },
                eventBonus = { event, context ->
                    if (event == null) {
                        0.0
                    } else {
                        val durationMinutes = max(0L, event.end - event.start) / 60_000.0
                        val hasMeetingCue = context.uniqueTokens.contains("meeting") || context.uniqueTokens.contains("agenda")
                        if (hasMeetingCue && durationMinutes >= 15) 2.0 else if (hasMeetingCue) 1.5 else 0.5
                    }
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.SHOPPING_LIST,
                keywordWeights = mapOf(
                    "shopping" to 2.5,
                    "list" to 2.0,
                    "buy" to 2.5,
                    "purchase" to 2.0,
                    "groceries" to 2.5,
                    "grocery" to 2.5,
                    "store" to 1.0,
                    "need" to 1.0,
                    "pick" to 1.0,
                    "pack" to 1.0,
                    "items" to 0.5,
                    "supplies" to 1.0
                ),
                phraseWeights = mapOf(
                    "to buy" to 2.0,
                    "need to get" to 2.0,
                    "packing list" to 2.5
                ),
                structuralBonus = { context ->
                    val shortLines = context.lines.count { line ->
                        val trimmed = line.trim()
                        trimmed.isNotEmpty() && trimmed.length <= 25
                    }
                    val bulletLines = context.lines.count { line ->
                        val trimmed = line.trimStart()
                        trimmed.startsWith("-") || trimmed.startsWith("*") || trimmed.startsWith("•")
                    }
                    val bonus = if (shortLines >= 3) 1.5 else 0.0
                    bonus + if (bulletLines >= 2) 1.0 else 0.0
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.REMINDER,
                keywordWeights = mapOf(
                    "reminder" to 3.0,
                    "remember" to 2.5,
                    "due" to 2.0,
                    "deadline" to 2.5,
                    "submit" to 1.5,
                    "call" to 1.0,
                    "email" to 1.0,
                    "tomorrow" to 1.5,
                    "today" to 1.0,
                    "follow" to 0.5,
                    "up" to 0.5,
                    "urgent" to 2.0
                ),
                phraseWeights = mapOf(
                    "don't forget" to 3.0,
                    "dont forget" to 3.0,
                    "be sure" to 1.5
                ),
                structuralBonus = { context ->
                    val hasExclamation = context.originalText.contains("!")
                    if (hasExclamation) 0.5 else 0.0
                },
                eventBonus = { event, context ->
                    if (event == null) {
                        0.0
                    } else {
                        val hasReminderCues = context.uniqueTokens.any { it in setOf("reminder", "remember", "due") }
                        if (hasReminderCues) 2.0 else 1.0
                    }
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.JOURNAL_ENTRY,
                keywordWeights = mapOf(
                    "journal" to 3.0,
                    "diary" to 2.5,
                    "today" to 2.0,
                    "feeling" to 2.0,
                    "felt" to 1.5,
                    "mood" to 1.5,
                    "reflect" to 2.0,
                    "grateful" to 2.5,
                    "learned" to 1.5,
                    "experience" to 1.0,
                    "thoughts" to 1.0
                ),
                phraseWeights = mapOf(
                    "i am grateful" to 3.0,
                    "i feel" to 2.0,
                    "today was" to 2.0
                ),
                structuralBonus = { context ->
                    val hasFirstPerson = context.tokens.any { it in setOf("i", "im", "me", "my") }
                    if (hasFirstPerson) 1.5 else 0.0
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.TRAVEL_PLAN,
                keywordWeights = mapOf(
                    "travel" to 2.5,
                    "itinerary" to 3.0,
                    "flight" to 3.0,
                    "train" to 2.0,
                    "hotel" to 2.5,
                    "checkin" to 1.5,
                    "checkout" to 1.5,
                    "departure" to 2.0,
                    "arrival" to 2.0,
                    "reservation" to 2.5,
                    "packing" to 1.5,
                    "passport" to 1.5,
                    "gate" to 1.0,
                    "boarding" to 1.5
                ),
                phraseWeights = mapOf(
                    "flight number" to 2.5,
                    "hotel booking" to 2.5,
                    "car rental" to 2.0,
                    "check in" to 1.5,
                    "check out" to 1.5
                ),
                structuralBonus = { context ->
                    val containsDates = context.originalText.contains(Regex("\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", RegexOption.IGNORE_CASE))
                    val containsTimes = context.originalText.contains(Regex("\\b\\d{1,2}(:\\d{2})?\\s?(am|pm)?\\b", RegexOption.IGNORE_CASE))
                    var bonus = 0.0
                    if (containsDates) bonus += 1.5
                    if (containsTimes) bonus += 1.0
                    bonus
                }
            )
        )
    }
}

/**
 * Structured output produced by [NoteNatureClassifier].
 */
data class NoteNatureLabel(
    val type: NoteNatureType,
    val humanReadable: String,
    val confidence: Double
)

/**
 * Enumerated taxonomy for [NoteNatureClassifier].
 */
enum class NoteNatureType(val humanReadable: String) {
    MEETING_RECAP("Meeting recap and action items"),
    SHOPPING_LIST("Shopping or packing list"),
    REMINDER("Reminder or follow-up"),
    JOURNAL_ENTRY("Personal journal reflection"),
    TRAVEL_PLAN("Travel or itinerary plan"),
    GENERAL_NOTE("General note overview")
}
