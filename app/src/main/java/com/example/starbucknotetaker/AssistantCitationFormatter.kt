package com.example.starbucknotetaker

import java.util.Locale

/** Places research citations beside the paragraph or list item supported by each source. */
internal object AssistantCitationFormatter {
    private val markdownLinkRegex = Regex("""\[([^\]]+)]\((https://[^)\s]+)\)""")
    private val sourceHeadingRegex = Regex(
        """^\s*(?:#{1,6}\s*)?(?:sources?|references?)\s*:?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val listItemRegex = Regex("""^\s*(?:[-*+]|\d+[.)])\s+.+$""")
    private val wordRegex = Regex("""[\p{L}\p{N}]{3,}""")

    fun format(answer: String, webLookup: WebLookupResult): String {
        val normalized = answer.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return normalized

        val (body, trailingCitations) = removeTrailingSourceSection(normalized)
        val blocks = body.split(Regex("""\n\s*\n"""))
            .filter(String::isNotBlank)
            .map(::toBlock)
            .toMutableList()
        val units = blocks.flatMap(CitationBlock::units)
        if (units.isEmpty()) return body

        units.forEach { unit ->
            val links = markdownLinkRegex.findAll(unit.text).toList()
            links.forEach { match ->
                unit.addCitation(match.groupValues[2], match.groupValues[1])
            }
            unit.text = cleanAfterCitationRemoval(
                markdownLinkRegex.replace(unit.text, ""),
            )
        }

        val resultsByUrl = webLookup.results.associateBy { canonicalUrl(it.url) }
        val eligibleUnits = units.filter { isEligibleForCitation(it.text) }

        webLookup.results.forEach { result ->
            val sourceWords = words("${result.title} ${result.snippet}")
            val scored = eligibleUnits.map { unit ->
                unit to overlapScore(sourceWords, words(unit.text))
            }
            val relevant = scored.filter { (_, score) ->
                score >= minimumOverlap(sourceWords)
            }
            relevant.forEach { (unit, _) ->
                unit.addCitation(result.url, websiteName(result.url))
            }
        }

        trailingCitations.forEach { citation ->
            val result = resultsByUrl[canonicalUrl(citation.url)]
            val sourceWords = result?.let { words("${it.title} ${it.snippet}") }.orEmpty()
            val scored = eligibleUnits.map { unit ->
                unit to overlapScore(sourceWords, words(unit.text))
            }
            val relevant = scored.filter { (_, score) ->
                sourceWords.isNotEmpty() && score >= minimumOverlap(sourceWords)
            }
            if (relevant.isNotEmpty()) {
                relevant.forEach { (unit, _) -> unit.addCitation(citation.url, citation.label) }
            } else {
                scored.maxByOrNull { it.second }?.first
                    ?.addCitation(citation.url, citation.label)
            }
        }

        val hasAnyCitation = units.any { it.citations.isNotEmpty() }
        if (!hasAnyCitation && webLookup.results.isNotEmpty()) {
            val primary = webLookup.results.first()
            val target = eligibleUnits.maxByOrNull { unit ->
                overlapScore(words("${primary.title} ${primary.snippet}"), words(unit.text))
            }
            target?.addCitation(primary.url, websiteName(primary.url))
        }

        return blocks.joinToString("\n\n") { block ->
            block.units.joinToString(if (block.isList) "\n" else "") { it.render() }
        }.trim()
    }

    private fun toBlock(paragraph: String): CitationBlock {
        val lines = paragraph.lines()
        val isList = lines.size > 1 && lines.all(listItemRegex::matches)
        val units = if (isList) lines.map(::CitationUnit) else listOf(CitationUnit(paragraph))
        return CitationBlock(units = units, isList = isList)
    }

    private fun removeTrailingSourceSection(markdown: String): SourceSectionResult {
        val lines = markdown.lines()
        for (index in lines.indices.reversed()) {
            if (!sourceHeadingRegex.matches(lines[index])) continue
            val trailing = lines.drop(index + 1)
            if (trailing.none { markdownLinkRegex.containsMatchIn(it) }) continue
            if (trailing.any { it.isNotBlank() && !isCitationOnlyLine(it) }) continue
            val citations = trailing.flatMap { line ->
                markdownLinkRegex.findAll(line).map { match ->
                    Citation(match.groupValues[1], match.groupValues[2])
                }.toList()
            }
            return SourceSectionResult(
                body = lines.take(index).joinToString("\n").trim(),
                citations = citations,
            )
        }
        return SourceSectionResult(markdown, emptyList())
    }

    private fun isCitationOnlyLine(line: String): Boolean {
        val withoutLinks = markdownLinkRegex.replace(line, "")
        return withoutLinks.all { character ->
            character.isWhitespace() || character == '\u2022' ||
                character in "-*+.,;:()[]0123456789"
        }
    }

    private fun cleanAfterCitationRemoval(value: String): String = value
        .replace(Regex("""[ \t]+([,.;:!?])"""), "$1")
        .replace(Regex("""\(\s*\)"""), "")
        .replace(Regex("""[ \t]{2,}"""), " ")
        .trimEnd()

    private fun isEligibleForCitation(value: String): Boolean =
        value.isNotBlank() &&
            !(value.trimStart().startsWith("#") && value.lines().size == 1) &&
            words(value).isNotEmpty()

    private fun words(value: String): Set<String> =
        wordRegex.findAll(value.lowercase(Locale.US))
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toSet()

    private fun overlapScore(first: Set<String>, second: Set<String>): Int =
        first.count(second::contains)

    private fun minimumOverlap(sourceWords: Set<String>): Int =
        if (sourceWords.size < 5) 1 else 2

    private fun canonicalUrl(url: String): String = url.trim().trimEnd('/').lowercase(Locale.US)

    private data class SourceSectionResult(
        val body: String,
        val citations: List<Citation>,
    )

    private data class Citation(
        val label: String,
        val url: String,
    )

    private data class CitationBlock(
        val units: List<CitationUnit>,
        val isList: Boolean,
    )

    private data class CitationUnit(
        var text: String,
        val citations: MutableList<Citation> = mutableListOf(),
    ) {
        fun addCitation(url: String, label: String) {
            if (citations.none { canonicalUrl(it.url) == canonicalUrl(url) }) {
                citations += Citation(label.ifBlank { websiteName(url) }, url)
            }
        }

        fun render(): String {
            if (citations.isEmpty()) return text.trimEnd()
            val links = citations.joinToString(" ") { citation ->
                "[${citation.label}](${citation.url})"
            }
            return "${text.trimEnd()} $links".trim()
        }
    }

    private val STOP_WORDS = setOf(
        "and", "are", "but", "for", "from", "has", "have", "into", "not", "that", "the",
        "their", "then", "there", "these", "this", "was", "were", "with", "you", "your",
    )
}
