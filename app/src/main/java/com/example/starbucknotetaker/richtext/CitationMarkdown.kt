package com.example.starbucknotetaker.richtext

import java.util.Locale

/**
 * Carries semantic citation URLs through the plain-Markdown boundary used by the AI reformatter.
 *
 * Notes store the visible link label in [RichTextDocument.text] and the URL in a
 * [RichTextStyle.Citation] span. Sending only `text` to the model therefore loses the URL.
 */
internal object CitationMarkdown {
    private val markdownLinkRegex = Regex("""\[([^\]]+)]\((https://[^)\s]+)\)""")
    private val wordRegex = Regex("""[\p{L}\p{N}]{3,}""")

    fun encode(document: RichTextDocument): String {
        val ranges = citationRanges(document)
        if (ranges.isEmpty()) return document.text

        return buildString {
            var cursor = 0
            ranges.forEach { range ->
                append(document.text, cursor, range.start)
                val label = document.text.substring(range.start, range.end)
                append('[')
                append(label)
                append("](")
                append(range.url)
                append(')')
                cursor = range.end
            }
            append(document.text, cursor, document.text.length)
        }
    }

    /**
     * Restores links present in [source] when a model returns otherwise valid Markdown without them.
     *
     * A source URL is required once per source paragraph. The original label is wrapped at its
     * closest matching rewritten paragraph; if rewording removed that label, a citation is appended
     * to the paragraph whose vocabulary most closely matches the original context.
     */
    fun preserve(source: RichTextDocument, rewrittenMarkdown: String): String {
        val anchors = citationAnchors(source)
        if (anchors.isEmpty()) return rewrittenMarkdown.trim()

        var output = rewrittenMarkdown.trim()
        anchors.forEach { anchor ->
            val target = closestParagraph(output, anchor)
            if (target != null && paragraphContainsLink(output, target, anchor.url)) return@forEach
            output = restoreAnchor(output, anchor)
        }
        return output.trim()
    }

    private fun restoreAnchor(markdown: String, anchor: CitationAnchor): String {
        val paragraphs = paragraphRanges(markdown)
        if (paragraphs.isEmpty()) {
            return "${markdown.trim()} ${anchor.markdownLink()}".trim()
        }
        val target = closestParagraph(markdown, anchor) ?: paragraphs.last()
        val existingLinkRanges = markdownLinkRegex.findAll(markdown).map { it.range }.toList()
        val labelMatch = Regex(Regex.escape(anchor.label), RegexOption.IGNORE_CASE)
            .findAll(markdown, target.start)
            .takeWhile { it.range.first < target.end }
            .filterNot { match -> existingLinkRanges.any { match.range.first in it } }
            .lastOrNull()

        if (labelMatch != null) {
            val matchedLabel = markdown.substring(labelMatch.range)
            return markdown.replaceRange(
                labelMatch.range,
                "[$matchedLabel](${anchor.url})",
            )
        }

        var insertion = target.end
        while (insertion > target.start && markdown[insertion - 1].isWhitespace()) insertion--
        return buildString(markdown.length + anchor.url.length + anchor.label.length + 5) {
            append(markdown, 0, insertion)
            if (isNotEmpty() && lastOrNull()?.isWhitespace() == false) append(' ')
            append(anchor.markdownLink())
            append(markdown, insertion, markdown.length)
        }
    }

    private fun citationAnchors(document: RichTextDocument): List<CitationAnchor> {
        val paragraphs = paragraphRanges(document.text)
        return citationRanges(document)
            .map { range ->
                val paragraph = paragraphs.firstOrNull { range.start in it.start until it.end }
                CitationAnchor(
                    label = document.text.substring(range.start, range.end),
                    url = range.url,
                    paragraphIndex = paragraphs.indexOf(paragraph),
                    contextWords = words(paragraph?.text.orEmpty()),
                )
            }
            .distinctBy { it.url to it.paragraphIndex }
    }

    private fun citationRanges(document: RichTextDocument): List<CitationRange> {
        val candidates = document.spans.flatMap { range ->
            range.styles.filterIsInstance<RichTextStyle.Citation>().map { citation ->
                CitationRange(range.start, range.end, citation.url)
            }
        }.sortedWith(compareBy<CitationRange> { it.start }.thenBy { it.end })
        return buildList {
            candidates.forEach { candidate ->
                val previous = lastOrNull()
                if (previous == null || candidate.start >= previous.end) add(candidate)
            }
        }
    }

    private fun paragraphRanges(text: String): List<ParagraphRange> {
        if (text.isBlank()) return emptyList()
        val separators = Regex("""\n\s*\n""").findAll(text).toList()
        val paragraphs = mutableListOf<ParagraphRange>()
        var start = 0
        separators.forEach { separator ->
            if (separator.range.first > start) {
                paragraphs += ParagraphRange(
                    start = start,
                    end = separator.range.first,
                    text = text.substring(start, separator.range.first),
                )
            }
            start = separator.range.last + 1
        }
        if (start < text.length) {
            paragraphs += ParagraphRange(start, text.length, text.substring(start))
        }
        return paragraphs.filter { it.text.isNotBlank() }
    }

    private fun closestParagraph(markdown: String, anchor: CitationAnchor): ParagraphRange? =
        paragraphRanges(markdown)
            .filterNot { it.text.trimStart().startsWith("#") && it.text.lines().size == 1 }
            .maxByOrNull { paragraph -> overlapScore(anchor.contextWords, words(paragraph.text)) }

    private fun paragraphContainsLink(
        markdown: String,
        paragraph: ParagraphRange,
        url: String,
    ): Boolean = markdownLinkRegex.findAll(markdown, paragraph.start)
        .takeWhile { it.range.first < paragraph.end }
        .any { it.groupValues[2] == url }

    private fun words(value: String): Set<String> =
        wordRegex.findAll(value.lowercase(Locale.US))
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toSet()

    private fun overlapScore(first: Set<String>, second: Set<String>): Int =
        first.count(second::contains)

    private data class CitationRange(
        val start: Int,
        val end: Int,
        val url: String,
    )

    private data class CitationAnchor(
        val label: String,
        val url: String,
        val paragraphIndex: Int,
        val contextWords: Set<String>,
    ) {
        fun markdownLink(): String = "[$label]($url)"
    }

    private data class ParagraphRange(
        val start: Int,
        val end: Int,
        val text: String,
    )

    private val STOP_WORDS = setOf(
        "and", "are", "but", "for", "from", "has", "have", "into", "not", "that", "the",
        "their", "then", "there", "these", "this", "was", "were", "with", "you", "your",
    )
}
