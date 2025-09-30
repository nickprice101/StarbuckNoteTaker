package com.example.starbucknotetaker

import java.text.Normalizer
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.max

private val HIGHLIGHT_STOP_WORDS = setOf(
    "agenda",
    "action",
    "actions",
    "assignment",
    "assignments",
    "chores",
    "entry",
    "entries",
    "goal",
    "goals",
    "items",
    "item",
    "list",
    "lists",
    "meeting",
    "meetings",
    "note",
    "notes",
    "plan",
    "plans",
    "project",
    "projects",
    "report",
    "reports",
    "schedule",
    "task",
    "tasks",
    "todo",
    "update",
    "updates"
)

/**
 * Provides lightweight note intent classification for the app. The taxonomy is intentionally
 * compact but now spans the most common scenarios surfaced in user research:
 *
 * 1. [NoteNatureType.PERSONAL_DAILY_LIFE] &ndash; everyday plans, social updates, and lifestyle notes.
 * 2. [NoteNatureType.FINANCE_LEGAL] &ndash; budgets, invoices, policies, and legal checklists.
 * 3. [NoteNatureType.SELF_IMPROVEMENT] &ndash; habit trackers, affirmations, and growth goals.
 * 4. [NoteNatureType.HEALTH_WELLNESS] &ndash; workouts, medication logs, and meal planning.
 * 5. [NoteNatureType.EDUCATION_LEARNING] &ndash; lecture notes, study guides, and assignments.
 * 6. [NoteNatureType.HOME_FAMILY] &ndash; household coordination and family schedules.
 * 7. [NoteNatureType.MEETING_RECAP] &ndash; work meetings, minutes, and action items.
 * 8. [NoteNatureType.SHOPPING_LIST] &ndash; shopping or packing checklists.
 * 9. [NoteNatureType.REMINDER] &ndash; short, time-sensitive prompts or follow ups.
 * 10. [NoteNatureType.JOURNAL_ENTRY] &ndash; reflective journal or diary style notes.
 * 11. [NoteNatureType.TRAVEL_PLAN] &ndash; itineraries, reservations, and logistics.
 * 12. [NoteNatureType.PROJECT_MANAGEMENT] &ndash; roadmaps, milestones, and delivery trackers.
 * 13. [NoteNatureType.EVENT_PLANNING] &ndash; guest lists, venue coordination, and celebration prep.
 * 14. [NoteNatureType.FOOD_RECIPE] &ndash; ingredient lists and cooking instructions.
 * 15. [NoteNatureType.CREATIVE_WRITING] &ndash; story drafts, character notes, and plot outlines.
 * 16. [NoteNatureType.TECHNICAL_REFERENCE] &ndash; troubleshooting logs and configuration docs.
 * 17. [NoteNatureType.COUNTRY_LIST] &ndash; geography collections and travel inspiration.
 * 18. [NoteNatureType.NEWS_REPORT] &ndash; structured reportage and current event briefs.
 * 19. [NoteNatureType.GENERAL_NOTE] &ndash; catch-all fallback when confidence is low.
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
        val definition = categoryDefinitionMap[type]
        return definition?.buildLabel(context, confidence)
            ?: NoteNatureLabel(type, type.humanReadable, confidence)
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
        val eventBonus: (NoteEvent?, NormalizedContext) -> Double = { _, _ -> 0.0 },
        val labelBuilder: ((NormalizedContext, Double) -> NoteNatureLabel)? = null
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

        fun buildLabel(context: NormalizedContext, confidence: Double): NoteNatureLabel {
            val builder = labelBuilder ?: defaultLabelBuilder(type)
            val built = builder(context, confidence)
            return if (built.type == type) built else built.copy(type = type, confidence = confidence)
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

        private fun defaultLabelBuilder(type: NoteNatureType): (NormalizedContext, Double) -> NoteNatureLabel {
            return { context, confidence ->
                val description = buildDefaultDescription(type, context)
                NoteNatureLabel(type, description, confidence)
            }
        }

        private fun buildDefaultDescription(type: NoteNatureType, context: NormalizedContext): String {
            val base = type.humanReadable
            val highlights = extractHighlights(context, 3)
            if (highlights.isEmpty()) {
                return base
            }
            val connector = when (type) {
                NoteNatureType.FINANCE_LEGAL -> "detailing"
                NoteNatureType.SELF_IMPROVEMENT, NoteNatureType.HEALTH_WELLNESS, NoteNatureType.PROJECT_MANAGEMENT -> "tracking"
                NoteNatureType.MEETING_RECAP -> "highlighting"
                NoteNatureType.REMINDER, NoteNatureType.EDUCATION_LEARNING -> "about"
                NoteNatureType.JOURNAL_ENTRY -> "reflecting on"
                NoteNatureType.FOOD_RECIPE, NoteNatureType.CREATIVE_WRITING -> "featuring"
                NoteNatureType.TECHNICAL_REFERENCE -> "covering"
                NoteNatureType.GENERAL_NOTE -> "mentioning"
                else -> "covering"
            }
            val useSuchAs = when (type) {
                NoteNatureType.PERSONAL_DAILY_LIFE,
                NoteNatureType.TRAVEL_PLAN,
                NoteNatureType.EVENT_PLANNING,
                NoteNatureType.HOME_FAMILY,
                NoteNatureType.FOOD_RECIPE,
                NoteNatureType.CREATIVE_WRITING,
                NoteNatureType.GENERAL_NOTE -> true
                else -> false
            }
            val highlightText = formatList(highlights)
            val phrase = if (useSuchAs) {
                "such as $highlightText"
            } else {
                highlightText
            }
            return "$base $connector $phrase"
        }

        private fun extractHighlights(context: NormalizedContext, limit: Int): List<String> {
            if (limit <= 0) return emptyList()
            val seen = LinkedHashSet<String>()
            val sorted = context.tokenFrequency
                .asSequence()
                .filter { (token, _) ->
                    token.length >= 3 &&
                        token.any { it.isLetter() } &&
                        token.none { it.isDigit() } &&
                        token !in HIGHLIGHT_STOP_WORDS
                }
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenByDescending { it.key.length }
                        .thenBy { it.key }
                )
                .map { prettifyToken(it.key) }
                .iterator()
            val highlights = mutableListOf<String>()
            while (sorted.hasNext() && highlights.size < limit) {
                val candidate = sorted.next()
                val lowered = candidate.lowercase(Locale.ROOT)
                if (seen.add(lowered)) {
                    highlights.add(candidate)
                }
            }
            return highlights
        }

        private fun prettifyToken(token: String): String {
            if (token.length <= 1) return token
            return token.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }

        private fun formatList(items: List<String>): String {
            if (items.isEmpty()) return ""
            if (items.size == 1) return items.first()
            if (items.size == 2) return items.joinToString(" and ")
            val prefix = items.dropLast(1).joinToString(", ")
            return "$prefix and ${items.last()}"
        }

        private fun normalizeListEntry(text: String): String {
            var cleaned = text.trim()
            cleaned = cleaned.replaceFirst(Regex("^[\\-*•]+\\s*"), "")
            cleaned = cleaned.replaceFirst(Regex("^\\d+[.)]\\s*"), "")
            return cleaned.trim()
        }

        private val COUNTRY_NAMES = listOf(
            "Argentina",
            "Australia",
            "Brazil",
            "Canada",
            "Chile",
            "China",
            "Colombia",
            "France",
            "Germany",
            "India",
            "Italy",
            "Japan",
            "Mexico",
            "Peru",
            "Spain",
            "United Kingdom",
            "United States",
            "South Africa"
        )

        private val COUNTRY_KEYWORD_WEIGHTS = buildMap {
            put("country", 2.0)
            put("countries", 2.5)
            put("nation", 1.5)
            put("nations", 1.5)
            for (country in COUNTRY_NAMES) {
                val normalized = country.lowercase(Locale.ROOT)
                val token = normalized.substringBefore(" ")
                put(token, 1.0)
            }
            put("travel", 0.5)
            put("visited", 1.0)
            put("visa", 0.5)
            put("capital", 0.5)
        }

        private val categoryDefinitions = listOf(
            CategoryDefinition(
                type = NoteNatureType.PERSONAL_DAILY_LIFE,
                keywordWeights = mapOf(
                    "today" to 1.5,
                    "tonight" to 1.5,
                    "weekend" to 2.0,
                    "plans" to 1.5,
                    "plan" to 1.0,
                    "daily" to 1.5,
                    "life" to 1.0,
                    "errands" to 2.5,
                    "chores" to 2.0,
                    "dinner" to 1.5,
                    "brunch" to 1.5,
                    "hangout" to 1.5,
                    "gathering" to 1.5,
                    "movie" to 1.5,
                    "concert" to 1.5,
                    "tickets" to 1.0,
                    "friends" to 1.0,
                    "outing" to 1.5,
                    "celebration" to 1.5,
                    "birthday" to 2.5,
                    "weeknight" to 1.0,
                    "schedule" to 1.0,
                    "appointment" to 1.5,
                    "pickup" to 1.0
                ),
                phraseWeights = mapOf(
                    "daily routine" to 2.5,
                    "weekend plans" to 2.5,
                    "to do today" to 2.5
                ),
                structuralBonus = { context ->
                    val dayCueRegex = Regex(
                        "\\b(?:today|tonight|tomorrow|weekend|morning|afternoon|evening)\\b",
                        RegexOption.IGNORE_CASE
                    )
                    val hasDayCue = dayCueRegex.containsMatchIn(context.originalText)
                    val hasSocialCue = context.tokens.any { it in setOf("friends", "family", "dinner", "brunch") }
                    var bonus = 0.0
                    if (hasDayCue) bonus += 1.0
                    if (hasSocialCue) bonus += 0.5
                    bonus
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.FINANCE_LEGAL,
                keywordWeights = mapOf(
                    "budget" to 2.5,
                    "invoice" to 3.0,
                    "invoices" to 2.5,
                    "expense" to 2.5,
                    "expenses" to 2.5,
                    "payment" to 2.0,
                    "payments" to 2.0,
                    "tax" to 3.0,
                    "taxes" to 3.0,
                    "contract" to 2.5,
                    "agreement" to 2.0,
                    "policy" to 1.5,
                    "policies" to 1.5,
                    "legal" to 2.5,
                    "compliance" to 1.5,
                    "insurance" to 2.0,
                    "claim" to 2.0,
                    "claims" to 2.0,
                    "premium" to 1.5,
                    "billing" to 2.0,
                    "receipt" to 1.5,
                    "receipts" to 1.5,
                    "ledger" to 2.0,
                    "loan" to 2.0,
                    "mortgage" to 2.0,
                    "balance" to 1.5,
                    "due" to 1.0
                ),
                phraseWeights = mapOf(
                    "terms and conditions" to 3.0,
                    "due date" to 2.5,
                    "statement of work" to 3.0,
                    "payment schedule" to 2.5
                ),
                structuralBonus = { context ->
                    val currencyRegex = Regex("[\\$€£¥]\\s?\\d")
                    val hasCurrency = currencyRegex.containsMatchIn(context.originalText)
                    val hasSectionLanguage = context.originalText.contains(
                        Regex("\\b(section|clause|article)\\b", RegexOption.IGNORE_CASE)
                    )
                    val numericDense = context.originalText.contains(Regex("\\b\\d{3,}\\b"))
                    var bonus = 0.0
                    if (hasCurrency) bonus += 1.5
                    if (numericDense) bonus += 0.5
                    if (hasSectionLanguage) bonus += 0.5
                    bonus
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.SELF_IMPROVEMENT,
                keywordWeights = mapOf(
                    "goal" to 1.5,
                    "goals" to 1.5,
                    "habit" to 2.5,
                    "habits" to 2.5,
                    "routine" to 1.5,
                    "practice" to 1.0,
                    "progress" to 1.5,
                    "improvement" to 2.0,
                    "motivation" to 1.5,
                    "affirmation" to 2.0,
                    "affirmations" to 2.0,
                    "focus" to 1.0,
                    "vision" to 1.0,
                    "tracker" to 1.5,
                    "reflection" to 1.0,
                    "milestone" to 1.5,
                    "mindset" to 1.5,
                    "discipline" to 1.5
                ),
                phraseWeights = mapOf(
                    "habit tracker" to 3.0,
                    "personal growth" to 2.5,
                    "weekly goals" to 2.0,
                    "growth plan" to 2.0
                ),
                structuralBonus = { context ->
                    val checkboxCount = context.lines.count { line ->
                        line.contains("[ ]") || line.contains("[x]", ignoreCase = true)
                    }
                    val hasProgressPercent = context.originalText.contains(Regex("\\b\\d{1,3}%\\b"))
                    var bonus = 0.0
                    if (checkboxCount >= 1) bonus += 1.0
                    if (hasProgressPercent) bonus += 0.5
                    bonus
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.HEALTH_WELLNESS,
                keywordWeights = mapOf(
                    "health" to 1.5,
                    "wellness" to 1.5,
                    "workout" to 2.5,
                    "exercise" to 2.0,
                    "training" to 2.0,
                    "cardio" to 2.0,
                    "run" to 1.0,
                    "yoga" to 2.0,
                    "meditation" to 1.5,
                    "medication" to 2.5,
                    "dose" to 1.5,
                    "dosage" to 1.5,
                    "calories" to 1.5,
                    "nutrition" to 1.5,
                    "meal" to 1.5,
                    "hydration" to 1.5,
                    "sleep" to 1.0,
                    "symptoms" to 2.0,
                    "therapy" to 1.5,
                    "vitals" to 2.0,
                    "doctor" to 2.0,
                    "clinic" to 1.5,
                    "appointment" to 1.5,
                    "stretch" to 1.0,
                    "reps" to 1.0
                ),
                phraseWeights = mapOf(
                    "meal plan" to 2.5,
                    "training session" to 2.0,
                    "blood pressure" to 2.5,
                    "medication schedule" to 2.5
                ),
                structuralBonus = { context ->
                    val unitRegex = Regex(
                        "\\b\\d+(?:\\.\\d+)?\\s?(?:km|mi|lbs|lb|kg|mg|bpm|cal|kcal|minutes)\\b",
                        RegexOption.IGNORE_CASE
                    )
                    val hasUnit = unitRegex.containsMatchIn(context.originalText)
                    val mealHeader = context.lines.any { line ->
                        val trimmed = line.trimStart()
                        trimmed.startsWith("breakfast", ignoreCase = true) ||
                            trimmed.startsWith("lunch", ignoreCase = true) ||
                            trimmed.startsWith("dinner", ignoreCase = true)
                    }
                    var bonus = 0.0
                    if (hasUnit) bonus += 1.5
                    if (mealHeader) bonus += 0.5
                    bonus
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.EDUCATION_LEARNING,
                keywordWeights = mapOf(
                    "lecture" to 2.5,
                    "class" to 2.0,
                    "course" to 2.0,
                    "lesson" to 1.5,
                    "study" to 2.0,
                    "studying" to 2.0,
                    "assignment" to 2.5,
                    "homework" to 2.5,
                    "exam" to 2.5,
                    "quiz" to 2.0,
                    "syllabus" to 2.0,
                    "research" to 1.5,
                    "theory" to 1.5,
                    "concepts" to 1.0,
                    "notes" to 1.0,
                    "seminar" to 1.5,
                    "tutorial" to 1.5,
                    "worksheet" to 1.5,
                    "revision" to 1.5
                ),
                phraseWeights = mapOf(
                    "study guide" to 3.0,
                    "key concepts" to 2.0,
                    "practice problems" to 2.0,
                    "reading list" to 2.0
                ),
                structuralBonus = { context ->
                    val numberedSections = context.lines.count { line ->
                        val trimmed = line.trimStart()
                        trimmed.matches(Regex("(lesson|chapter|module)\\s+\\d+", RegexOption.IGNORE_CASE)) ||
                            trimmed.matches(Regex("\\d+[.)].*"))
                    }
                    val hasStudyCue = context.originalText.contains(
                        Regex("\\bsyllabus\\b|\\bstudy guide\\b", RegexOption.IGNORE_CASE)
                    )
                    var bonus = 0.0
                    if (numberedSections >= 1) bonus += 1.0
                    if (hasStudyCue) bonus += 0.5
                    bonus
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.HOME_FAMILY,
                keywordWeights = mapOf(
                    "family" to 2.5,
                    "house" to 2.0,
                    "home" to 1.5,
                    "household" to 2.0,
                    "kids" to 2.0,
                    "children" to 2.0,
                    "parents" to 1.5,
                    "mom" to 1.5,
                    "dad" to 1.5,
                    "grandparents" to 2.0,
                    "laundry" to 2.0,
                    "cleaning" to 2.0,
                    "chores" to 2.0,
                    "tidy" to 1.5,
                    "babysitter" to 2.0,
                    "carpool" to 1.5,
                    "schedule" to 1.0,
                    "dropoff" to 1.5,
                    "pickup" to 1.5,
                    "playdate" to 1.5,
                    "daycare" to 1.5,
                    "mealplan" to 1.0
                ),
                phraseWeights = mapOf(
                    "family schedule" to 3.0,
                    "household chores" to 2.5,
                    "meal prep" to 2.0,
                    "family meeting" to 2.0
                ),
                structuralBonus = { context ->
                    val timeRegex = Regex("\\b\\d{1,2}:\\d{2}\\s?(?:am|pm)?\\b", RegexOption.IGNORE_CASE)
                    val hasTime = timeRegex.containsMatchIn(context.originalText)
                    val familyMentions = context.tokens.count { it in setOf("family", "kids", "parents", "mom", "dad", "grandparents") }
                    var bonus = 0.0
                    if (hasTime) bonus += 0.5
                    if (familyMentions >= 2) {
                        bonus += 1.0
                    } else if (familyMentions == 1) {
                        bonus += 0.5
                    }
                    bonus
                }
            ),
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
                    "summary" to 1.0,
                    "sync" to 1.5,
                    "standup" to 1.5,
                    "retro" to 1.5
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
                    "market" to 1.5,
                    "pharmacy" to 1.5,
                    "store" to 1.0,
                    "need" to 1.0,
                    "pick" to 1.0,
                    "pack" to 1.0,
                    "items" to 0.5,
                    "supplies" to 1.0,
                    "cart" to 1.0
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
                },
                labelBuilder = { context, confidence ->
                    val trimmedLines = context.lines.map { it.trim() }
                    val bulletItems = trimmedLines.filter { line ->
                        line.isNotEmpty() && (
                            line.startsWith("-") ||
                                line.startsWith("*") ||
                                line.startsWith("•") ||
                                line.matches(Regex("\\d+\\. .*"))
                            )
                    }
                    val compactLines = trimmedLines.filter { line ->
                        line.isNotEmpty() && line.length <= 32
                    }
                    val normalizedItems = (bulletItems + compactLines)
                        .map { normalizeListEntry(it) }
                        .filter { it.isNotEmpty() }
                    val itemCount = max(bulletItems.size, compactLines.size)
                        .coerceAtLeast(normalizedItems.size)
                    val previewSet = LinkedHashSet<String>()
                    val previewItems = mutableListOf<String>()
                    for (item in normalizedItems) {
                        val lowered = item.lowercase(Locale.ROOT)
                        if (previewSet.add(lowered)) {
                            previewItems.add(item)
                        }
                        if (previewItems.size >= 3) break
                    }
                    val noun = if (itemCount == 1) "item" else "items"
                    val baseDescription = if (itemCount > 0) {
                        "Shopping list containing $itemCount $noun"
                    } else {
                        NoteNatureType.SHOPPING_LIST.humanReadable
                    }
                    val description = if (previewItems.isNotEmpty()) {
                        "$baseDescription, such as ${formatList(previewItems)}"
                    } else {
                        baseDescription
                    }
                    NoteNatureLabel(NoteNatureType.SHOPPING_LIST, description, confidence)
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
                    "urgent" to 2.0,
                    "notify" to 1.5,
                    "pay" to 1.5,
                    "renew" to 1.5
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
                    "thoughts" to 1.0,
                    "memories" to 1.5,
                    "emotions" to 1.5,
                    "gratitude" to 2.0
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
                    "boarding" to 1.5,
                    "airbnb" to 1.5,
                    "excursion" to 1.5,
                    "tour" to 1.5,
                    "roadtrip" to 1.5
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
            ),
            CategoryDefinition(
                type = NoteNatureType.PROJECT_MANAGEMENT,
                keywordWeights = mapOf(
                    "project" to 2.5,
                    "projects" to 2.0,
                    "milestone" to 2.5,
                    "milestones" to 2.5,
                    "deliverable" to 3.0,
                    "deliverables" to 3.0,
                    "timeline" to 2.0,
                    "roadmap" to 2.0,
                    "sprint" to 2.5,
                    "backlog" to 2.0,
                    "task" to 1.5,
                    "tasks" to 1.5,
                    "owner" to 1.0,
                    "owners" to 1.0,
                    "status" to 1.0,
                    "update" to 1.0,
                    "updates" to 1.0,
                    "stakeholder" to 1.5,
                    "stakeholders" to 1.5,
                    "dependency" to 1.5,
                    "dependencies" to 1.5,
                    "launch" to 1.0,
                    "release" to 1.0,
                    "okr" to 2.0,
                    "kanban" to 1.5,
                    "initiative" to 1.5,
                    "scrum" to 1.5
                ),
                phraseWeights = mapOf(
                    "project plan" to 3.0,
                    "delivery plan" to 2.0,
                    "risk register" to 2.5,
                    "sprint planning" to 2.5,
                    "sprint retrospective" to 2.5,
                    "status update" to 2.0
                ),
                structuralBonus = { context ->
                    val ownerMentions = context.lines.count { line ->
                        line.contains("owner", ignoreCase = true) ||
                            line.contains("due", ignoreCase = true)
                    }
                    val milestoneHeaders = context.lines.count { line ->
                        line.trimStart().matches(
                            Regex("(milestone|phase)\\s+\\d+", RegexOption.IGNORE_CASE)
                        )
                    }
                    var bonus = 0.0
                    if (ownerMentions >= 1) bonus += 0.5
                    if (ownerMentions >= 3) bonus += 0.5
                    if (milestoneHeaders >= 1) bonus += 0.5
                    bonus
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.EVENT_PLANNING,
                keywordWeights = mapOf(
                    "event" to 2.5,
                    "party" to 2.5,
                    "wedding" to 3.0,
                    "ceremony" to 2.0,
                    "reception" to 2.0,
                    "celebration" to 1.5,
                    "guests" to 2.5,
                    "guest" to 2.0,
                    "rsvp" to 3.0,
                    "venue" to 2.5,
                    "caterer" to 2.0,
                    "catering" to 2.0,
                    "decor" to 1.5,
                    "decorations" to 1.5,
                    "seating" to 2.0,
                    "playlist" to 1.5,
                    "entertainment" to 1.5,
                    "timeline" to 1.5,
                    "schedule" to 1.0,
                    "invitation" to 1.5,
                    "invites" to 1.5,
                    "photographer" to 2.0,
                    "anniversary" to 2.0,
                    "baby" to 1.5,
                    "shower" to 1.5
                ),
                phraseWeights = mapOf(
                    "guest list" to 3.0,
                    "save the date" to 2.5,
                    "seating chart" to 2.5,
                    "vendor outreach" to 2.0,
                    "day-of timeline" to 2.5
                ),
                structuralBonus = { context ->
                    val hasDate = context.originalText.contains(
                        Regex("\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b", RegexOption.IGNORE_CASE)
                    ) || context.originalText.contains(Regex("\\b\\d{1,2}/\\d{1,2}\\b"))
                    val guestLineCount = context.lines.count { line ->
                        line.contains("guest", ignoreCase = true) ||
                            line.contains("rsvp", ignoreCase = true)
                    }
                    var bonus = 0.0
                    if (hasDate) bonus += 0.5
                    if (guestLineCount >= 1) bonus += 0.5
                    if (guestLineCount >= 3) bonus += 0.5
                    bonus
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.FOOD_RECIPE,
                keywordWeights = mapOf(
                    "recipe" to 3.0,
                    "recipes" to 2.5,
                    "ingredients" to 3.0,
                    "ingredient" to 2.0,
                    "servings" to 2.0,
                    "serves" to 1.5,
                    "preheat" to 2.5,
                    "bake" to 2.0,
                    "simmer" to 2.0,
                    "boil" to 1.5,
                    "sauté" to 1.5,
                    "saute" to 1.5,
                    "mix" to 1.0,
                    "whisk" to 1.5,
                    "stir" to 1.0,
                    "teaspoon" to 1.5,
                    "tablespoon" to 1.5,
                    "cup" to 1.0,
                    "cups" to 1.0,
                    "oven" to 1.0,
                    "skillet" to 1.0,
                    "marinate" to 1.5,
                    "broth" to 1.5,
                    "grill" to 1.5,
                    "roast" to 1.5
                ),
                phraseWeights = mapOf(
                    "ingredient list" to 3.0,
                    "cooking instructions" to 2.5,
                    "step 1" to 1.5,
                    "step 2" to 1.5,
                    "step 3" to 1.5,
                    "bake until" to 2.0
                ),
                structuralBonus = { context ->
                    val hasIngredientsHeader = context.lines.any { line ->
                        line.trim().startsWith("ingredients", ignoreCase = true)
                    }
                    val hasInstructionsHeader = context.lines.any { line ->
                        line.trim().startsWith("instructions", ignoreCase = true) ||
                            line.trim().startsWith("directions", ignoreCase = true)
                    }
                    var bonus = 0.0
                    if (hasIngredientsHeader) bonus += 1.0
                    if (hasInstructionsHeader) bonus += 0.5
                    bonus
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.CREATIVE_WRITING,
                keywordWeights = mapOf(
                    "story" to 2.5,
                    "stories" to 2.0,
                    "novel" to 2.5,
                    "chapter" to 3.0,
                    "chapters" to 2.5,
                    "scene" to 2.5,
                    "scenes" to 2.0,
                    "character" to 3.0,
                    "characters" to 3.0,
                    "protagonist" to 2.5,
                    "antagonist" to 2.5,
                    "plot" to 2.0,
                    "narrative" to 1.5,
                    "dialogue" to 2.0,
                    "outline" to 1.5,
                    "draft" to 1.5,
                    "poem" to 1.5,
                    "poetry" to 1.5,
                    "screenplay" to 2.0,
                    "script" to 1.5,
                    "arc" to 1.0,
                    "theme" to 1.0,
                    "setting" to 1.5
                ),
                phraseWeights = mapOf(
                    "short story" to 3.0,
                    "character profile" to 3.0,
                    "chapter outline" to 2.5,
                    "world building" to 2.0
                ),
                structuralBonus = { context ->
                    val chapterHeaders = context.lines.count { line ->
                        line.trimStart().matches(
                            Regex("(chapter|scene)\\s+\\d+", RegexOption.IGNORE_CASE)
                        )
                    }
                    val quoteUsage = context.originalText.count { it == '"' }
                    var bonus = 0.0
                    if (chapterHeaders >= 1) bonus += 0.5
                    if (chapterHeaders >= 2) bonus += 0.5
                    if (quoteUsage >= 4) bonus += 0.5
                    bonus
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.TECHNICAL_REFERENCE,
                keywordWeights = mapOf(
                    "api" to 2.5,
                    "endpoint" to 2.5,
                    "server" to 1.5,
                    "database" to 1.5,
                    "query" to 1.5,
                    "config" to 2.0,
                    "configuration" to 2.0,
                    "deploy" to 1.5,
                    "deployment" to 1.5,
                    "build" to 1.0,
                    "debug" to 1.5,
                    "error" to 2.0,
                    "errors" to 2.0,
                    "exception" to 2.0,
                    "stack" to 1.0,
                    "trace" to 1.0,
                    "log" to 1.0,
                    "logs" to 1.0,
                    "command" to 1.5,
                    "commands" to 1.5,
                    "script" to 1.5,
                    "scripts" to 1.5,
                    "docker" to 2.0,
                    "kubernetes" to 2.0,
                    "ssh" to 1.5,
                    "terminal" to 1.0,
                    "cli" to 1.0,
                    "bug" to 1.5,
                    "issue" to 1.5,
                    "patch" to 1.0,
                    "rollback" to 1.5,
                    "yaml" to 1.5,
                    "json" to 1.5,
                    "configmap" to 1.5,
                    "monitoring" to 1.5
                ),
                phraseWeights = mapOf(
                    "steps to reproduce" to 3.0,
                    "error code" to 2.5,
                    "api request" to 2.0,
                    "expected behavior" to 2.0,
                    "actual behavior" to 2.0,
                    "stack trace" to 2.5
                ),
                structuralBonus = { context ->
                    val codeFence = context.lines.any { line -> line.trim().startsWith("```") }
                    val commandLines = context.lines.count { line ->
                        val trimmed = line.trimStart()
                        trimmed.startsWith("$ ") ||
                            trimmed.startsWith("curl ", ignoreCase = true) ||
                            trimmed.startsWith("GET ", ignoreCase = true)
                    }
                    var bonus = 0.0
                    if (codeFence) bonus += 0.5
                    if (commandLines >= 1) bonus += 0.5
                    if (commandLines >= 3) bonus += 0.5
                    bonus
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.COUNTRY_LIST,
                keywordWeights = COUNTRY_KEYWORD_WEIGHTS,
                phraseWeights = mapOf(
                    "country list" to 3.0,
                    "list of countries" to 3.0,
                    "visited countries" to 2.5,
                    "countries to visit" to 2.5
                ),
                structuralBonus = { context ->
                    val bulletLines = context.lines.count { line ->
                        val trimmed = line.trimStart()
                        trimmed.startsWith("-") || trimmed.startsWith("*") || trimmed.startsWith("•") ||
                            trimmed.matches(Regex("\\d+\\. .*"))
                    }
                    val recognizedCountries = countCountryMentions(context)
                    var bonus = 0.0
                    if (bulletLines >= 3) bonus += 1.5
                    if (recognizedCountries >= 3) {
                        bonus += 2.0
                    } else if (recognizedCountries >= 1) {
                        bonus += 0.5
                    }
                    bonus
                },
                labelBuilder = { context, confidence ->
                    val recognized = extractCountryMentions(context)
                    val bulletLines = context.lines.count { line ->
                        val trimmed = line.trimStart()
                        trimmed.startsWith("-") || trimmed.startsWith("*") || trimmed.startsWith("•") ||
                            trimmed.matches(Regex("\\d+\\. .*"))
                    }
                    val entryCount = max(recognized.size, bulletLines)
                    val noun = if (entryCount == 1) "entry" else "entries"
                    val preview = recognized.take(3)
                    val highlights = if (preview.isNotEmpty()) {
                        ", including ${formatList(preview)}"
                    } else {
                        ""
                    }
                    val description = if (entryCount > 0) {
                        "Country list with $entryCount $noun$highlights"
                    } else {
                        NoteNatureType.COUNTRY_LIST.humanReadable
                    }
                    NoteNatureLabel(NoteNatureType.COUNTRY_LIST, description, confidence)
                }
            ),
            CategoryDefinition(
                type = NoteNatureType.NEWS_REPORT,
                keywordWeights = mapOf(
                    "breaking" to 2.5,
                    "news" to 3.0,
                    "report" to 2.5,
                    "headline" to 2.0,
                    "update" to 1.5,
                    "officials" to 1.0,
                    "witnesses" to 1.0,
                    "reported" to 1.5,
                    "according" to 0.5,
                    "incident" to 1.5,
                    "investigation" to 1.5,
                    "alert" to 1.5,
                    "live" to 0.5,
                    "article" to 1.5,
                    "coverage" to 1.5,
                    "journalists" to 1.0
                ),
                phraseWeights = mapOf(
                    "breaking news" to 3.5,
                    "developing story" to 3.0,
                    "according to" to 1.5,
                    "news report" to 3.0,
                    "press conference" to 2.5
                ),
                structuralBonus = { context ->
                    val datelineRegex = Regex("^[A-Z]{2,}(?:, [A-Z]{2,})? - ")
                    val hasDateline = context.lines.firstOrNull()?.trim()?.let { datelineRegex.containsMatchIn(it) } == true
                    val paragraphCount = context.originalText.split("\n\n").count { it.trim().isNotEmpty() }
                    val quoteRegex = Regex("\"[^\"]+\"")
                    val hasQuote = quoteRegex.containsMatchIn(context.originalText)
                    val numericPrefixes = context.lines.count { line ->
                        line.trimStart().matches(Regex("\\d+[:.)].*"))
                    }
                    var bonus = 0.0
                    if (hasDateline) bonus += 1.5
                    if (paragraphCount >= 2) bonus += 1.0
                    if (hasQuote) bonus += 0.5
                    if (numericPrefixes >= 1) bonus += 0.5
                    bonus
                },
                labelBuilder = { context, confidence ->
                    val highlights = extractHighlights(context, 3)
                    val description = if (highlights.isNotEmpty()) {
                        "News report covering ${formatList(highlights)}"
                    } else {
                        NoteNatureType.NEWS_REPORT.humanReadable
                    }
                    NoteNatureLabel(NoteNatureType.NEWS_REPORT, description, confidence)
                }
            )
        )

        private val categoryDefinitionMap = categoryDefinitions.associateBy { it.type }

        private fun countCountryMentions(context: NormalizedContext): Int {
            val normalizedJoined = context.joined
            var total = 0
            for (country in COUNTRY_NAMES) {
                val normalizedCountry = country.lowercase(Locale.ROOT)
                if (normalizedJoined.contains(normalizedCountry)) {
                    total += 1
                }
            }
            return total
        }

        private fun extractCountryMentions(context: NormalizedContext): List<String> {
            val normalizedJoined = context.joined
            val mentions = mutableListOf<String>()
            for (country in COUNTRY_NAMES) {
                val normalizedCountry = country.lowercase(Locale.ROOT)
                if (normalizedJoined.contains(normalizedCountry)) {
                    mentions.add(country)
                }
            }
            return mentions
        }
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
    PERSONAL_DAILY_LIFE("Personal & daily life plans"),
    FINANCE_LEGAL("Finance & legal record"),
    SELF_IMPROVEMENT("Self-improvement & habits"),
    HEALTH_WELLNESS("Health & wellness tracker"),
    EDUCATION_LEARNING("Education & learning notes"),
    HOME_FAMILY("Home & family organization"),
    MEETING_RECAP("Work & meeting recap"),
    SHOPPING_LIST("Shopping & supplies list"),
    REMINDER("Reminders & follow-ups"),
    JOURNAL_ENTRY("Personal journal reflection"),
    TRAVEL_PLAN("Travel & leisure plan"),
    PROJECT_MANAGEMENT("Project management roadmap"),
    EVENT_PLANNING("Event & celebration planning"),
    FOOD_RECIPE("Recipe & cooking instructions"),
    CREATIVE_WRITING("Creative writing draft"),
    TECHNICAL_REFERENCE("Technical troubleshooting & docs"),
    COUNTRY_LIST("Country list overview"),
    NEWS_REPORT("News & current events brief"),
    GENERAL_NOTE("General note overview")
}
