package com.example.starbucknotetaker.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.starbucknotetaker.richtext.RichTextStyle

class RichTextState(initialValue: RichTextValue) {
    var value by mutableStateOf(initialValue)
        private set

    var activeStyles by mutableStateOf(value.stylesAt(value.selection))
        private set

    fun setExternalValue(newValue: RichTextValue) {
        value = newValue
        activeStyles = value.stylesAt(value.selection)
    }

    fun asTextFieldValue(): TextFieldValue {
        val annotated = value.toDocument().toAnnotatedString()
        return TextFieldValue(annotated, value.selection)
    }

    fun updateFromTextField(newValue: TextFieldValue) {
        val oldText = value.text
        val newText = newValue.text
        val oldSelection = value.selection
        val prefixLength = oldText.commonPrefixWith(newText).length
        val oldSuffixPortion = oldText.substring(prefixLength)
        val newSuffixPortion = newText.substring(prefixLength)
        val suffixLength = oldSuffixPortion.commonSuffixWith(newSuffixPortion).length
        val removedLength = oldText.length - prefixLength - suffixLength
        val insertedLength = newText.length - prefixLength - suffixLength
        val newStyles = value.characterStyles.toMutableList()
        repeat(removedLength) {
            if (prefixLength < newStyles.size) {
                newStyles.removeAt(prefixLength)
            }
        }
        if (insertedLength > 0) {
            val insertStyles = List(insertedLength) { activeStyles.toSet() }
            newStyles.addAll(prefixLength, insertStyles)
        }
        while (newStyles.size < newText.length) {
            newStyles.add(emptySet())
        }
        val adjustedStyles = newStyles.take(newText.length)
        val adjustedSelection = TextRange(
            newValue.selection.start.coerceIn(0, newText.length),
            newValue.selection.end.coerceIn(0, newText.length),
        )
        value = RichTextValue(newText, adjustedSelection, adjustedStyles)
        val selectionChanged = oldSelection != adjustedSelection
        if (selectionChanged) {
            activeStyles = value.stylesAt(adjustedSelection)
        }
    }

    fun toggleStyle(style: RichTextStyle) {
        val selection = value.selection
        if (selection.start == selection.end) {
            activeStyles = if (activeStyles.contains(style)) {
                activeStyles - style
            } else {
                activeStyles + style
            }
            return
        }
        val start = selection.start.coerceAtMost(selection.end).coerceIn(0, value.text.length)
        val end = selection.end.coerceAtLeast(selection.start).coerceIn(0, value.text.length)
        val shouldAdd = !value.rangeHasStyle(style, start, end)
        val updated = value.characterStyles.toMutableList()
        for (index in start until end) {
            val set = updated.getOrNull(index)?.toMutableSet() ?: mutableSetOf()
            if (shouldAdd) {
                set.add(style)
            } else {
                set.remove(style)
            }
            if (index < updated.size) {
                updated[index] = set
            }
        }
        value = value.copy(characterStyles = updated)
        activeStyles = value.stylesAt(selection)
    }

    fun applyFormattingAction(action: FormattingAction) {
        val newValue = when (action) {
            FormattingAction.BulletList -> value.applyListFormatting { "- " }
            FormattingAction.NumberedList -> value.applyListFormatting { index -> "${index + 1}. " }
        }
        if (newValue != value) {
            value = newValue
        }
        activeStyles = value.stylesAt(value.selection)
    }
}

private fun RichTextValue.applyListFormatting(
    prefixGenerator: (index: Int) -> String,
): RichTextValue {
    if (text.isEmpty()) return this
    val start = selection.start.coerceAtMost(selection.end).coerceIn(0, text.length)
    val end = selection.end.coerceAtLeast(selection.start).coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', start - 1).let { if (it == -1) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', end).let { if (it == -1) text.length else it }
    if (lineStart >= lineEnd) return this

    val replacementBuilder = StringBuilder()
    val replacementStyles = mutableListOf<Set<RichTextStyle>>()
    var lineIndex = 0
    var current = lineStart
    while (current < lineEnd) {
        val nextBreakRaw = text.indexOf('\n', current)
        val nextBreak = when {
            nextBreakRaw == -1 -> lineEnd
            nextBreakRaw > lineEnd -> lineEnd
            else -> nextBreakRaw
        }
        val lineText = text.substring(current, nextBreak)
        val firstNonWhitespace = lineText.indexOfFirst { !it.isWhitespace() }
        val indentLength = if (firstNonWhitespace == -1) lineText.length else firstNonWhitespace
        val trimmedStart = current + indentLength
        val prefix = if (indentLength == lineText.length) {
            ""
        } else {
            prefixGenerator(lineIndex)
        }

        if (indentLength > 0) {
            for (i in current until trimmedStart.coerceAtMost(nextBreak)) {
                replacementBuilder.append(text[i])
                replacementStyles.add(characterStyles.getOrNull(i) ?: emptySet())
            }
        }

        if (prefix.isNotEmpty()) {
            replacementBuilder.append(prefix)
            repeat(prefix.length) { replacementStyles.add(emptySet()) }
        }

        for (i in trimmedStart until nextBreak) {
            replacementBuilder.append(text[i])
            replacementStyles.add(characterStyles.getOrNull(i) ?: emptySet())
        }

        if (nextBreak < lineEnd) {
            replacementBuilder.append('\n')
            replacementStyles.add(characterStyles.getOrNull(nextBreak) ?: emptySet())
        }

        current = if (nextBreak < lineEnd) nextBreak + 1 else lineEnd
        lineIndex++
    }

    val replacement = replacementBuilder.toString()
    val newText = text.replaceRange(lineStart, lineEnd, replacement)
    val newStyles = characterStyles.toMutableList()
    val removeCount = lineEnd - lineStart
    repeat(removeCount) {
        if (lineStart < newStyles.size) {
            newStyles.removeAt(lineStart)
        }
    }
    newStyles.addAll(lineStart, replacementStyles)
    val newSelection = TextRange(lineStart, lineStart + replacement.length)
    return copy(text = newText, selection = newSelection, characterStyles = newStyles)
}
