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

    fun handleListEnter(): RichTextValue? {
        if (!selection.collapsed) return null
        if (text.isEmpty()) return null
        val caret = selection.start.coerceIn(0, text.length)
        val context = listItemContextAt(caret) ?: return null
        if (caret < context.prefixStart) return null

        val textBuilder = StringBuilder(text)
        val stylesBuilder = characterStyles.toMutableList()

        val indent = if (context.indent.isEmpty()) DEFAULT_LIST_INDENT else context.indent
        val contentBlank = text.substring(context.contentStart, context.lineEnd).isBlank()
        return if (contentBlank && caret >= context.contentStart) {
            val indentLength = context.indent.length
            var prefixStart = context.prefixStart
            var contentStart = context.contentStart
            var caretAfterAdjustments = caret
            if (indentLength > 0) {
                textBuilder.delete(context.lineStart, context.prefixStart)
                repeat(indentLength) { stylesBuilder.removeAt(context.lineStart) }
                prefixStart -= indentLength
                contentStart -= indentLength
                caretAfterAdjustments = (caretAfterAdjustments - indentLength).coerceAtLeast(context.lineStart)
            }
            val removalStart = prefixStart
            val removalEnd = contentStart
            val removedCount = removalEnd - removalStart
            if (removedCount > 0) {
                textBuilder.delete(removalStart, removalEnd)
                repeat(removedCount) { stylesBuilder.removeAt(removalStart) }
            }
            val caretAfterRemoval = (caretAfterAdjustments - removedCount).coerceAtLeast(removalStart)
            textBuilder.insert(caretAfterRemoval, '\n')
            stylesBuilder.add(caretAfterRemoval, emptySet())
            RichTextValue(
                text = textBuilder.toString(),
                selection = TextRange(caretAfterRemoval + 1),
                characterStyles = stylesBuilder,
            )
        } else {
            val nextPrefix = when (context.type) {
                ListItemType.Bullet -> context.prefixText
                ListItemType.Numbered -> context.nextNumberPrefix()
            }
            val insertPosition = caret
            val insertText = buildString {
                append('\n')
                append(indent)
                append(nextPrefix)
            }
            val insertStyles = List(insertText.length) { emptySet<RichTextStyle>() }
            textBuilder.insert(insertPosition, insertText)
            stylesBuilder.addAll(insertPosition, insertStyles)
            RichTextValue(
                text = textBuilder.toString(),
                selection = TextRange(insertPosition + insertText.length),
                characterStyles = stylesBuilder,
            )
        }
    }

    internal fun applyListFormatting(prefix: String, type: ListItemType): RichTextValue {
        val caret = selection.start.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', caret - 1).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', caret).let { if (it == -1) text.length else it }
        val lineText = text.substring(lineStart, lineEnd)
        val indentLength = lineText.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) lineText.length else it }
        val existingIndent = if (indentLength > 0) lineText.substring(0, indentLength) else ""
        val indentToUse = existingIndent.ifEmpty { DEFAULT_LIST_INDENT }
        val originalPrefixStart = lineStart + indentLength
        val existing = listItemContextAt(originalPrefixStart)

        val targetPrefix = when (type) {
            ListItemType.Bullet -> prefix
            ListItemType.Numbered -> prefix
        }

        val textBuilder = StringBuilder(text)
        val stylesBuilder = characterStyles.toMutableList()

        val indentInserted = existingIndent.isEmpty() && indentToUse.isNotEmpty()
        if (indentInserted) {
            textBuilder.insert(lineStart, indentToUse)
            stylesBuilder.addAll(lineStart, List(indentToUse.length) { emptySet<RichTextStyle>() })
        }

        val prefixStart = lineStart + indentToUse.length

        val replaceStart = prefixStart
        val replaceEnd = existing?.contentStart?.let { it + (prefixStart - originalPrefixStart) } ?: prefixStart
        if (replaceEnd > replaceStart) {
            textBuilder.delete(replaceStart, replaceEnd)
            repeat(replaceEnd - replaceStart) { stylesBuilder.removeAt(replaceStart) }
        }
        textBuilder.insert(replaceStart, targetPrefix)
        stylesBuilder.addAll(replaceStart, List(targetPrefix.length) { emptySet() })

        val oldCaret = selection.start
        val caretAfterIndent = if (indentInserted && oldCaret >= lineStart) {
            oldCaret + indentToUse.length
        } else {
            oldCaret
        }
        val oldPrefixEnd = replaceEnd
        val newPrefixEnd = replaceStart + targetPrefix.length
        val caretAfterInsertion = when {
            caretAfterIndent <= replaceStart -> newPrefixEnd
            caretAfterIndent <= oldPrefixEnd -> newPrefixEnd
            else -> caretAfterIndent + targetPrefix.length - (oldPrefixEnd - replaceStart)
        }.coerceIn(0, textBuilder.length)

        return RichTextValue(
            text = textBuilder.toString(),
            selection = TextRange(caretAfterInsertion),
            characterStyles = stylesBuilder,
        )
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

internal enum class ListItemType {
    Bullet,
    Numbered,
}

internal data class ListItemContext(
    val type: ListItemType,
    val indent: String,
    val prefixText: String,
    val prefixStart: Int,
    val contentStart: Int,
    val lineStart: Int,
    val lineEnd: Int,
    val number: Int? = null,
    val numberSuffix: String = "",
)

internal fun ListItemContext.nextNumberPrefix(): String {
    val nextNumber = (number ?: 0) + 1
    return nextNumber.toString() + numberSuffix
}

private val bulletPrefixes = listOf("• ", "- ", "* ", "– ")

private const val DEFAULT_LIST_INDENT = "    "

internal fun RichTextValue.listItemContextAt(position: Int): ListItemContext? {
    if (text.isEmpty()) return null
    val caret = position.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', caret - 1).let { if (it == -1) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', caret).let { if (it == -1) text.length else it }
    if (lineStart >= lineEnd) return null
    val lineText = text.substring(lineStart, lineEnd)
    val firstNonWhitespace = lineText.indexOfFirst { !it.isWhitespace() }
    if (firstNonWhitespace == -1) return null
    val indent = lineText.substring(0, firstNonWhitespace)
    val afterIndent = lineText.substring(firstNonWhitespace)
    val prefixStart = lineStart + indent.length
    val bullet = bulletPrefixes.firstOrNull { afterIndent.startsWith(it) }
    if (bullet != null) {
        val contentStart = prefixStart + bullet.length
        return ListItemContext(
            type = ListItemType.Bullet,
            indent = indent,
            prefixText = bullet,
            prefixStart = prefixStart,
            contentStart = contentStart,
            lineStart = lineStart,
            lineEnd = lineEnd,
        )
    }
    val digits = afterIndent.takeWhile { it.isDigit() }
    if (digits.isNotEmpty()) {
        val number = digits.toIntOrNull() ?: return null
        var index = digits.length
        if (index >= afterIndent.length) return null
        val punctuation = afterIndent[index]
        if (!punctuation.isNumberPunctuation()) return null
        index++
        val whitespaceStart = index
        while (index < afterIndent.length && afterIndent[index].isWhitespace()) {
            index++
        }
        if (index == whitespaceStart) return null
        val suffix = afterIndent.substring(digits.length, index)
        val prefixText = digits + suffix
        val contentStart = prefixStart + prefixText.length
        return ListItemContext(
            type = ListItemType.Numbered,
            indent = indent,
            prefixText = prefixText,
            prefixStart = prefixStart,
            contentStart = contentStart,
            lineStart = lineStart,
            lineEnd = lineEnd,
            number = number,
            numberSuffix = suffix,
        )
    }
    return null
}

private fun Char.isNumberPunctuation(): Boolean = this == '.' || this == ')' || this == ']' || this == ':'
