package com.example.starbucknotetaker

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object QuickAssistantAnswerer {
    data class QuickAnswer(
        val answer: String,
        val status: String = "Answered from quick local knowledge",
    )

    private val countryCapitals = mapOf(
        "argentina" to "Buenos Aires",
        "australia" to "Canberra",
        "bangladesh" to "Dhaka",
        "belgium" to "Brussels",
        "brazil" to "Brasilia",
        "canada" to "Ottawa",
        "chile" to "Santiago",
        "china" to "Beijing",
        "colombia" to "Bogota",
        "denmark" to "Copenhagen",
        "egypt" to "Cairo",
        "finland" to "Helsinki",
        "france" to "Paris",
        "germany" to "Berlin",
        "greece" to "Athens",
        "iceland" to "Reykjavik",
        "india" to "New Delhi",
        "indonesia" to "Jakarta",
        "iran" to "Tehran",
        "ireland" to "Dublin",
        "italy" to "Rome",
        "japan" to "Tokyo",
        "kenya" to "Nairobi",
        "mexico" to "Mexico City",
        "netherlands" to "Amsterdam",
        "new zealand" to "Wellington",
        "nigeria" to "Abuja",
        "north korea" to "Pyongyang",
        "norway" to "Oslo",
        "pakistan" to "Islamabad",
        "peru" to "Lima",
        "philippines" to "Manila",
        "portugal" to "Lisbon",
        "russia" to "Moscow",
        "south africa" to "Pretoria",
        "south korea" to "Seoul",
        "spain" to "Madrid",
        "sweden" to "Stockholm",
        "thailand" to "Bangkok",
        "turkey" to "Ankara",
        "ukraine" to "Kyiv",
        "united kingdom" to "London",
        "uk" to "London",
        "united states" to "Washington, DC",
        "united states of america" to "Washington, DC",
        "usa" to "Washington, DC",
        "vietnam" to "Hanoi",
    )

    private val stateCapitals = mapOf(
        "california" to "Sacramento",
        "florida" to "Tallahassee",
        "new york" to "Albany",
        "texas" to "Austin",
        "washington" to "Olympia",
    )

    fun answer(
        question: String,
        noteContext: String? = null,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): QuickAnswer? {
        val trimmed = question.trim()
        if (trimmed.isBlank()) return null

        answerDateOrTime(trimmed, now)?.let { return it }
        answerArithmetic(trimmed)?.let { return it }
        answerCapital(trimmed)?.let { return it }
        answerNoteQuestion(trimmed, noteContext)?.let { return it }
        answerAssistantQuestion(trimmed)?.let { return it }

        return null
    }

    private fun answerCapital(question: String): QuickAnswer? {
        val match = CAPITAL_REGEX.find(question) ?: return null
        val place = normalizePlace(match.groupValues[1])
        val capital = countryCapitals[place] ?: stateCapitals[place] ?: return null
        val displayPlace = place.split(' ')
            .joinToString(" ") { it.replaceFirstChar { ch -> ch.titlecase(Locale.US) } }
        return QuickAnswer("The capital of $displayPlace is $capital.")
    }

    private fun answerArithmetic(question: String): QuickAnswer? {
        val compact = question
            .trim()
            .trimEnd('?', '.', '!')
            .replace(Regex("(?i)^(what(?:'s| is)|calculate|compute)\\s+"), "")
            .trim()
        val match = ARITHMETIC_REGEX.matchEntire(compact) ?: return null
        val left = match.groupValues[1].toBigDecimalOrNull() ?: return null
        val op = match.groupValues[2].lowercase(Locale.US)
        val right = match.groupValues[3].toBigDecimalOrNull() ?: return null
        val result = when (op) {
            "+", "plus" -> left + right
            "-", "minus" -> left - right
            "*", "x", "times" -> left * right
            "/", "over", "divided by" -> {
                if (right.compareTo(BigDecimal.ZERO) == 0) {
                    return QuickAnswer("That division is undefined because the divisor is zero.")
                }
                left.divide(right, 8, RoundingMode.HALF_UP).stripTrailingZeros()
            }
            else -> return null
        }
        return QuickAnswer("${formatNumber(left)} ${displayOperator(op)} ${formatNumber(right)} = ${formatNumber(result)}.")
    }

    private fun answerDateOrTime(question: String, now: ZonedDateTime): QuickAnswer? {
        val lower = question.lowercase(Locale.US)
        if (Regex("\\bwhat\\s+time\\b|\\btime\\s+is\\s+it\\b|\\bcurrent\\s+time\\b").containsMatchIn(lower)) {
            val formatted = now.format(DateTimeFormatter.ofPattern("h:mm a z", Locale.US))
            return QuickAnswer("The current time is $formatted.")
        }
        if (Regex("\\bwhat\\s+(?:is\\s+)?(?:the\\s+)?date\\b|\\btoday'?s\\s+date\\b|\\bwhat\\s+day\\b").containsMatchIn(lower)) {
            val formatted = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US))
            return QuickAnswer("Today is $formatted.")
        }
        return null
    }

    private fun answerNoteQuestion(question: String, noteContext: String?): QuickAnswer? {
        val note = noteContext?.trim().orEmpty()
        if (note.isBlank()) return null
        val lower = question.lowercase(Locale.US)
        if (lower.contains("word count") || lower.matches(Regex(".*\\bhow many words\\b.*"))) {
            val count = WORD_REGEX.findAll(note).count()
            return QuickAnswer("This note has $count words.")
        }
        if (
            lower.contains("summarize this note") ||
            lower.contains("summarise this note") ||
            lower.contains("what is this note about") ||
            lower.contains("what's this note about")
        ) {
            val preview = Summarizer.lightweightPreview(note).ifBlank { note.take(160) }
            return QuickAnswer(preview, status = "Summarized from note text")
        }
        return null
    }

    private fun answerAssistantQuestion(question: String): QuickAnswer? {
        val lower = question.lowercase(Locale.US)
        return when {
            lower.matches(Regex("\\s*(hi|hello|hey)\\s*[!.?]?\\s*")) ->
                QuickAnswer("Hi. I can help summarize notes, answer simple questions quickly, and use the on-device model for deeper requests.")
            lower.contains("who are you") || lower.contains("what can you do") ->
                QuickAnswer("I am the note assistant. I can answer simple questions quickly, look up current info when needed, and use the on-device model for richer note-aware responses.")
            else -> null
        }
    }

    private fun normalizePlace(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("\\b(the|country|state|city|capital)\\b"), " ")
            .replace(Regex("[^a-z\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun formatNumber(value: BigDecimal): String =
        value.stripTrailingZeros().toPlainString()

    private fun displayOperator(op: String): String = when (op) {
        "+", "plus" -> "+"
        "-", "minus" -> "-"
        "*", "x", "times" -> "*"
        else -> "/"
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        runCatching { BigDecimal(this) }.getOrNull()

    private val CAPITAL_REGEX = Regex(
        "(?i)\\b(?:what(?:'s|\\s+is)|whats|name)?\\s*(?:the\\s+)?capital\\s+of\\s+([a-zA-Z .'-]+)\\??\\s*$"
    )
    private val ARITHMETIC_REGEX = Regex(
        "([-+]?\\d+(?:\\.\\d+)?)\\s*(plus|minus|times|divided by|over|[+\\-*/x])\\s*([-+]?\\d+(?:\\.\\d+)?)",
        RegexOption.IGNORE_CASE,
    )
    private val WORD_REGEX = Regex("[A-Za-z0-9]+(?:['-][A-Za-z0-9]+)?")
}
