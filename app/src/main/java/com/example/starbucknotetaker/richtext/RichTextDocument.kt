package com.example.starbucknotetaker.richtext

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Represents styled text content using logical spans rather than inline markup.
 */
data class RichTextDocument(
    val text: String,
    val spans: List<StyleRange> = emptyList(),
) {
    init {
        require(spans.all { it.start in 0..text.length && it.end in 0..text.length && it.start <= it.end }) {
            "Span ranges must lie within the bounds of the text."
        }
    }

    fun toAnnotatedString(): AnnotatedString {
        if (spans.isEmpty()) {
            return AnnotatedString(text)
        }
        return buildAnnotatedString {
            append(text)
            spans.forEach { range ->
                range.styles.forEach { style ->
                    addStyle(style.toSpanStyle(), range.start, range.end)
                }
            }
        }
    }

    fun toCharacterStyles(): List<Set<RichTextStyle>> {
        if (text.isEmpty()) return emptyList()
        val charStyles = MutableList(text.length) { mutableSetOf<RichTextStyle>() }
        spans.forEach { range ->
            for (index in range.start until range.end.coerceAtMost(text.length)) {
                charStyles[index].addAll(range.styles)
            }
        }
        return charStyles.map { it.toSet() }
    }

    fun isBlank(): Boolean = text.isBlank()

    fun trimmed(): RichTextDocument {
        if (text.isEmpty()) return this
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first == -1) return RichTextDocument("")
        val last = text.indexOfLast { !it.isWhitespace() }
        val newText = text.substring(first, last + 1)
        val newSpans = spans.mapNotNull { range ->
            val start = range.start.coerceAtLeast(first)
            val end = range.end.coerceAtMost(last + 1)
            if (end <= start) return@mapNotNull null
            StyleRange(start - first, end - first, range.styles)
        }
        return RichTextDocument(newText, newSpans)
    }

    fun slice(start: Int, end: Int): RichTextDocument {
        if (start >= end || start >= text.length) return RichTextDocument("")
        val clampedStart = start.coerceAtMost(text.length)
        val clampedEnd = end.coerceAtMost(text.length)
        val newText = text.substring(clampedStart, clampedEnd)
        val newSpans = spans.mapNotNull { range ->
            val spanStart = range.start.coerceAtLeast(clampedStart)
            val spanEnd = range.end.coerceAtMost(clampedEnd)
            if (spanEnd <= spanStart) return@mapNotNull null
            StyleRange(spanStart - clampedStart, spanEnd - clampedStart, range.styles)
        }
        return RichTextDocument(newText, newSpans)
    }

    companion object {
        fun fromAnnotatedString(string: AnnotatedString): RichTextDocument {
            if (string.spanStyles.isEmpty()) {
                return RichTextDocument(string.text)
            }
            val ranges = string.spanStyles.map { styleRange ->
                StyleRange(
                    start = styleRange.start,
                    end = styleRange.end,
                    styles = setOfNotNull(styleRange.item.toRichTextStyle()),
                )
            }.filter { it.styles.isNotEmpty() }
            return RichTextDocument(string.text, ranges)
        }

        fun fromPlainText(text: String): RichTextDocument = RichTextDocument(text)
    }
}

private fun SpanStyle.toRichTextStyle(): RichTextStyle? = when {
    fontWeight == FontWeight.Bold -> RichTextStyle.Bold
    fontStyle == FontStyle.Italic -> RichTextStyle.Italic
    textDecoration == TextDecoration.Underline -> RichTextStyle.Underline
    else -> null
}

private fun RichTextStyle.toSpanStyle(): SpanStyle = when (this) {
    RichTextStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
    RichTextStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    RichTextStyle.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
}

/**
 * Represents a span of rich text styles applied over a [start, end) range.
 */
data class StyleRange(
    val start: Int,
    val end: Int,
    val styles: Set<RichTextStyle>,
) {
    init {
        require(start <= end) { "Invalid range" }
    }
}

class RichTextDocumentBuilder {
    private val text = StringBuilder()
    private val spans = mutableListOf<StyleRange>()

    fun append(document: RichTextDocument) {
        val offset = text.length
        text.append(document.text)
        document.spans.forEach { range ->
            if (range.styles.isNotEmpty() && range.start != range.end) {
                spans.add(
                    StyleRange(
                        start = offset + range.start,
                        end = offset + range.end,
                        styles = range.styles,
                    ),
                )
            }
        }
    }

    fun appendPlain(text: String) {
        this.text.append(text)
    }

    fun build(): RichTextDocument = RichTextDocument(text = text.toString(), spans = spans.toList())
}
