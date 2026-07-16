package com.example.starbucknotetaker.richtext

/**
 * Converts the small, predictable Markdown subset emitted by the on-device
 * assistant into the app's editable rich-text representation.
 *
 * Markdown markers are removed from the stored text while headings, emphasis,
 * lists, task items, links, and fenced blocks remain readable and editable.
 */
object MarkdownRichText {
    private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
    private val unorderedListRegex = Regex("^(\\s*)[-*+]\\s+(.+)$")
    private val orderedListRegex = Regex("^(\\s*)(\\d+)[.)]\\s+(.+)$")
    private val taskListRegex = Regex("^(\\s*)(?:[-*+]\\s+)?\\[([ xX])]\\s+(.+)$")
    private val blockQuoteRegex = Regex("^\\s*>\\s?(.*)$")
    private val horizontalRuleRegex = Regex("^\\s*(?:-{3,}|_{3,}|\\*{3,})\\s*$")

    fun parse(markdown: String): RichTextDocument {
        val normalized = markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) return RichTextDocument("")

        val accumulator = StyledTextAccumulator()
        var inFence = false
        val lines = normalized.lines()
        lines.forEachIndexed { index, originalLine ->
            val trimmed = originalLine.trim()
            if (trimmed.startsWith("```")) {
                inFence = !inFence
            } else {
                appendLine(originalLine, inFence, accumulator)
                if (index != lines.lastIndex) accumulator.append("\n")
            }
        }
        return accumulator.build().trimmed()
    }

    private fun appendLine(
        line: String,
        inFence: Boolean,
        output: StyledTextAccumulator,
    ) {
        if (inFence) {
            output.append(line)
            return
        }

        if (horizontalRuleRegex.matches(line)) {
            output.append("────────")
            return
        }

        headingRegex.matchEntire(line)?.let { match ->
            appendInline(match.groupValues[2], setOf(RichTextStyle.Bold), output)
            return
        }

        taskListRegex.matchEntire(line)?.let { match ->
            output.append(match.groupValues[1])
            output.append(if (match.groupValues[2].isBlank()) "☐ " else "☑ ")
            appendInline(match.groupValues[3], emptySet(), output)
            return
        }

        unorderedListRegex.matchEntire(line)?.let { match ->
            output.append(match.groupValues[1])
            output.append("• ")
            appendInline(match.groupValues[2], emptySet(), output)
            return
        }

        orderedListRegex.matchEntire(line)?.let { match ->
            output.append(match.groupValues[1])
            output.append("${match.groupValues[2]}. ")
            appendInline(match.groupValues[3], emptySet(), output)
            return
        }

        blockQuoteRegex.matchEntire(line)?.let { match ->
            output.append("❯ ", setOf(RichTextStyle.Italic))
            appendInline(match.groupValues[1], setOf(RichTextStyle.Italic), output)
            return
        }

        appendInline(line, emptySet(), output)
    }

    private fun appendInline(
        input: String,
        baseStyles: Set<RichTextStyle>,
        output: StyledTextAccumulator,
    ) {
        var index = 0
        while (index < input.length) {
            if (input[index] == '\\' && index + 1 < input.length) {
                output.append(input[index + 1].toString(), baseStyles)
                index += 2
                continue
            }

            val marker = when {
                input.startsWith("***", index) -> "***"
                input.startsWith("___", index) -> "___"
                input.startsWith("**", index) -> "**"
                input.startsWith("__", index) -> "__"
                input.startsWith("~~", index) -> "~~"
                input[index] == '*' -> "*"
                input[index] == '_' -> "_"
                else -> null
            }
            if (marker != null) {
                val close = input.indexOf(marker, index + marker.length)
                if (close > index + marker.length) {
                    val addedStyles = when (marker) {
                        "***", "___" -> setOf(RichTextStyle.Bold, RichTextStyle.Italic)
                        "**", "__" -> setOf(RichTextStyle.Bold)
                        "*", "_" -> setOf(RichTextStyle.Italic)
                        else -> emptySet()
                    }
                    appendInline(
                        input.substring(index + marker.length, close),
                        baseStyles + addedStyles,
                        output,
                    )
                    index = close + marker.length
                    continue
                }
            }

            if (input[index] == '`') {
                val close = input.indexOf('`', index + 1)
                if (close > index + 1) {
                    output.append(input.substring(index + 1, close), baseStyles)
                    index = close + 1
                    continue
                }
            }

            if (input[index] == '[') {
                val labelEnd = input.indexOf("](", index + 1)
                val urlEnd = if (labelEnd >= 0) input.indexOf(')', labelEnd + 2) else -1
                if (labelEnd > index + 1 && urlEnd > labelEnd + 2) {
                    appendInline(input.substring(index + 1, labelEnd), baseStyles, output)
                    val url = input.substring(labelEnd + 2, urlEnd).trim()
                    if (url.isNotBlank()) output.append(" ($url)", baseStyles)
                    index = urlEnd + 1
                    continue
                }
            }

            output.append(input[index].toString(), baseStyles)
            index++
        }
    }

    private class StyledTextAccumulator {
        private val text = StringBuilder()
        private val characterStyles = mutableListOf<Set<RichTextStyle>>()

        fun append(value: String, styles: Set<RichTextStyle> = emptySet()) {
            text.append(value)
            repeat(value.length) { characterStyles += styles }
        }

        fun build(): RichTextDocument {
            if (text.isEmpty()) return RichTextDocument("")
            val spans = mutableListOf<StyleRange>()
            var start = 0
            while (start < characterStyles.size) {
                val styles = characterStyles[start]
                var end = start + 1
                while (end < characterStyles.size && characterStyles[end] == styles) end++
                if (styles.isNotEmpty()) spans += StyleRange(start, end, styles)
                start = end
            }
            return RichTextDocument(text.toString(), spans)
        }
    }
}
