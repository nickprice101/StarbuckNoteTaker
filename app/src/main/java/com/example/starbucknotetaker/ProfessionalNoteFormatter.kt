package com.example.starbucknotetaker

import java.util.Locale

internal object ProfessionalNoteFormatter {
    private val rewriteRequestRegex = Regex(
        "\\b(rewrite|reformat|format|clean(?:\\s+(?:(?:this|the|current)(?:\\s+note)?|it))?\\s+up|professional|polish|tidy|organize|organise)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val noteReferenceRegex = Regex(
        "\\b(this|current|the)\\s+note\\b|\\bit\\b",
        RegexOption.IGNORE_CASE,
    )
    private val bulletRegex = Regex("^\\s*(?:[-*+]|\\d+[.)]|\\[[ xX]])\\s+")
    private val splitRegex = Regex("\\s*(?:\\n+|;|\\.\\s+|,\\s+(?=(?:and\\s+)?(?:ask|call|send|update|finish|finalize|follow|review|schedule|prepare|draft|clean|buy|order|move|check|confirm|email|message|book|replace)\\b))\\s*", RegexOption.IGNORE_CASE)
    private val actionRegex = Regex(
        "^(?:please\\s+)?(?:ask|call|send|update|finish|finalize|follow\\s+up|review|schedule|prepare|draft|clean|buy|order|move|check|confirm|email|message|book|replace|assign|note|mention)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val fillerPrefixRegex = Regex(
        "^(?:note:?|notes:?|todo:?|to-do:?|remember to|need to|please)\\s+",
        RegexOption.IGNORE_CASE,
    )
    private val trailingPunctuationRegex = Regex("[\\s,.;:!-]+$")

    fun isRewriteRequest(question: String): Boolean {
        val trimmed = question.trim()
        if (trimmed.isBlank()) return false
        return rewriteRequestRegex.containsMatchIn(trimmed) &&
            (noteReferenceRegex.containsMatchIn(trimmed) || trimmed.length <= 120)
    }

    fun format(content: String): String {
        val cleaned = cleanWhitespace(content)
        if (cleaned.isBlank()) return ""

        val fragments = extractFragments(cleaned)
        if (fragments.isEmpty()) {
            return punctuate(cleaned)
        }

        if (fragments.size <= 2) {
            return fragments.joinToString("\n\n") { punctuate(sentenceCase(it)) }
        }

        val actions = fragments.filter { actionRegex.containsMatchIn(it) }
        val details = fragments.filterNot { it in actions }
        val overview = (details.firstOrNull() ?: fragments.first()).limitWords(24)
        val bullets = (if (actions.isNotEmpty()) actions else fragments.drop(1))
            .map { sentenceCase(cleanFragment(it)).limitWords(18) }
            .distinctBy { it.lowercase(Locale.US) }
            .take(8)

        return buildString {
            appendLine("## Overview")
            appendLine(punctuate(sentenceCase(overview)))
            if (bullets.isNotEmpty()) {
                appendLine()
                appendLine(if (actions.isNotEmpty()) "## Action Items" else "## Key Points")
                bullets.forEach { bullet ->
                    appendLine("- ${punctuate(bullet)}")
                }
            }
        }.trim()
    }

    private fun extractFragments(content: String): List<String> {
        val lines = content.lines()
        val bulletItems = lines
            .mapNotNull { line ->
                bulletRegex.find(line)?.let {
                    line.substring(it.range.last + 1).trim()
                }
            }
            .filter { it.isNotBlank() }

        val base = if (bulletItems.size >= 2) {
            bulletItems
        } else {
            splitRegex.split(content)
        }

        return base
            .map { cleanFragment(it) }
            .filter { it.wordCount() >= 2 }
            .distinctBy { it.lowercase(Locale.US) }
    }

    private fun cleanWhitespace(text: String): String =
        text.replace("\r\n", "\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun cleanFragment(fragment: String): String =
        fragment.replace(bulletRegex, "")
            .replace(fillerPrefixRegex, "")
            .replace(Regex("\\s+"), " ")
            .replace(trailingPunctuationRegex, "")
            .trim()

    private fun punctuate(text: String): String {
        val cleaned = text.replace(trailingPunctuationRegex, "").trim()
        if (cleaned.isBlank()) return ""
        return if (cleaned.last() in ".!?") cleaned else "$cleaned."
    }

    private fun sentenceCase(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ""
        return trimmed.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
        }
    }

    private fun String.limitWords(maxWords: Int): String {
        val words = trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= maxWords) return trim()
        return words.take(maxWords).joinToString(" ")
    }

    private fun String.wordCount(): Int =
        trim().split(Regex("\\s+")).count { it.isNotBlank() }
}
