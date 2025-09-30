package com.example.starbucknotetaker.ui

import androidx.compose.ui.text.TextRange
import com.example.starbucknotetaker.richtext.RichTextDocument
import com.example.starbucknotetaker.richtext.RichTextStyle
import com.example.starbucknotetaker.richtext.RichTextDocumentBuilder

/**
 * Represents the editable state of a rich text field.
 */
data class RichTextValue(
    val text: String,
    val selection: TextRange,
    val characterStyles: List<Set<RichTextStyle>>,
) {
    init {
        require(characterStyles.size == text.length) {
            "characterStyles must contain one entry per character"
        }
    }

    fun toDocument(): RichTextDocument {
        if (text.isEmpty()) {
            return RichTextDocument(text = "")
        }
        val builder = RichTextDocumentBuilder()
        var index = 0
        while (index < text.length) {
            val styles = characterStyles.getOrElse(index) { emptySet() }
            val runEnd = findRunEnd(index, styles)
            if (styles.isEmpty()) {
                builder.appendPlain(text.substring(index, runEnd))
            } else {
                val fragment = RichTextDocument(
                    text = text.substring(index, runEnd),
                    spans = if (styles.isEmpty()) emptyList() else listOf(
                        com.example.starbucknotetaker.richtext.StyleRange(0, runEnd - index, styles),
                    ),
                )
                builder.append(fragment)
            }
            index = runEnd
        }
        return builder.build()
    }

    private fun findRunEnd(start: Int, styles: Set<RichTextStyle>): Int {
        var index = start
        while (index < text.length && characterStyles.getOrElse(index) { emptySet() } == styles) {
            index++
        }
        return index
    }

    fun stylesAt(range: TextRange): Set<RichTextStyle> {
        if (text.isEmpty()) return emptySet()
        val start = range.start.coerceIn(0, text.length)
        val end = range.end.coerceIn(0, text.length)
        if (start == end) {
            val caret = start.coerceAtLeast(1)
            return characterStyles.getOrNull(caret - 1)?.toSet() ?: emptySet()
        }
        var index = start
        var styles: Set<RichTextStyle>? = null
        while (index < end) {
            val current = characterStyles.getOrNull(index)?.toSet().orEmpty()
            styles = styles?.intersect(current) ?: current
            if (styles.isNullOrEmpty()) return emptySet()
            index++
        }
        return styles ?: emptySet()
    }

    fun rangeHasStyle(style: RichTextStyle, start: Int, end: Int): Boolean {
        if (start >= end) return false
        for (index in start until end) {
            if (!characterStyles.getOrNull(index).orEmpty().contains(style)) return false
        }
        return true
    }

    companion object {
        fun empty(): RichTextValue = RichTextValue("", TextRange.Zero, emptyList())

        fun fromDocument(document: RichTextDocument): RichTextValue {
            val styles = document.toCharacterStyles()
            return RichTextValue(
                text = document.text,
                selection = TextRange(document.text.length),
                characterStyles = styles,
            )
        }

        fun fromPlainText(text: String): RichTextValue {
            val chars = List(text.length) { emptySet<RichTextStyle>() }
            return RichTextValue(text, TextRange(text.length), chars)
        }
    }
}
