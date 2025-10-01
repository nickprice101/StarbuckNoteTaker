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
            FormattingAction.BulletList -> value.applyListFormatting("• ", ListItemType.Bullet)
            FormattingAction.NumberedList -> value.applyListFormatting("1. ", ListItemType.Numbered)
        }
        if (newValue != value) {
            value = newValue
        }
        activeStyles = value.stylesAt(value.selection)
    }

    fun handleEnterKey(): Boolean {
        val newValue = value.handleListEnter() ?: return false
        value = newValue
        activeStyles = value.stylesAt(value.selection)
        return true
    }
}
