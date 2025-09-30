package com.example.starbucknotetaker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

enum class FormattingAction {
    Bold,
    Italic,
    Underline,
    Header,
    TextColor,
    Highlight,
    BulletList,
    NumberedList,
}

@Composable
fun FormattingToolbar(
    visible: Boolean,
    onAction: (FormattingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(44.dp),
            color = MaterialTheme.colors.surface,
            elevation = 2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                ToolbarButton(Icons.Filled.FormatBold) { onAction(FormattingAction.Bold) }
                ToolbarButton(Icons.Filled.FormatItalic) { onAction(FormattingAction.Italic) }
                ToolbarButton(Icons.Filled.FormatUnderlined) { onAction(FormattingAction.Underline) }
                ToolbarButton(Icons.Filled.FormatSize) { onAction(FormattingAction.Header) }
                ToolbarButton(Icons.Filled.FormatColorText) { onAction(FormattingAction.TextColor) }
                ToolbarButton(Icons.Filled.Highlight) { onAction(FormattingAction.Highlight) }
                ToolbarButton(Icons.Filled.FormatListBulleted) { onAction(FormattingAction.BulletList) }
                ToolbarButton(Icons.Filled.FormatListNumbered) { onAction(FormattingAction.NumberedList) }
            }
        }
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

fun applyTextFormatting(value: TextFieldValue, action: FormattingAction): TextFieldValue {
    return when (action) {
        FormattingAction.Bold -> surroundSelection(value, "**", "**", "bold text")
        FormattingAction.Italic -> surroundSelection(value, "_", "_", "italic text")
        FormattingAction.Underline -> surroundSelection(value, "<u>", "</u>", "underlined text")
        FormattingAction.Header -> applyHeaderFormatting(value)
        FormattingAction.TextColor -> surroundSelection(
            value,
            "<span style=\"color:red\">",
            "</span>",
            "colored text",
        )
        FormattingAction.Highlight -> surroundSelection(value, "==", "==", "highlighted text")
        FormattingAction.BulletList -> applyListFormatting(value, prefixGenerator = { "- " })
        FormattingAction.NumberedList -> applyListFormatting(value) { index -> "${index + 1}. " }
    }
}

private fun surroundSelection(
    value: TextFieldValue,
    prefix: String,
    suffix: String,
    placeholder: String,
): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    val hasSelection = start != end
    val selectedText = if (hasSelection) {
        text.substring(start, end)
    } else {
        placeholder
    }
    val newText = buildString {
        append(text.substring(0, start))
        append(prefix)
        append(selectedText)
        append(suffix)
        append(text.substring(end))
    }
    val newSelectionStart = start + prefix.length
    val newSelectionEnd = newSelectionStart + selectedText.length
    return value.copy(text = newText, selection = TextRange(newSelectionStart, newSelectionEnd))
}

private fun applyHeaderFormatting(value: TextFieldValue): TextFieldValue {
    val text = value.text
    if (text.isEmpty()) return surroundSelection(value, "# ", "", "Heading")
    val selection = value.selection
    val caret = selection.start.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', caret - 1).let { if (it == -1) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', caret).let { if (it == -1) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val trimmed = line.trimStart()
    val indentLength = line.length - trimmed.length
    val indent = line.take(indentLength)
    val headerPrefix = "# "
    val newLine = if (trimmed.startsWith(headerPrefix)) {
        line
    } else {
        indent + headerPrefix + trimmed
    }
    val newText = text.replaceRange(lineStart, lineEnd, newLine)
    val delta = newLine.length - line.length
    val newStart = (selection.start + delta).coerceIn(0, newText.length)
    val newEnd = (selection.end + delta).coerceIn(0, newText.length)
    return value.copy(text = newText, selection = TextRange(newStart, newEnd))
}

private fun applyListFormatting(
    value: TextFieldValue,
    prefixGenerator: (index: Int) -> String,
): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val start = selection.start.coerceIn(0, text.length)
    val end = selection.end.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', (if (start == end) start else start) - 1).let { if (it == -1) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', end).let { if (it == -1) text.length else it }
    val block = text.substring(lineStart, lineEnd)
    val lines = block.split('\n')
    val formattedLines = lines.mapIndexed { index, line ->
        if (line.isBlank()) {
            line
        } else {
            val trimmed = line.trimStart()
            val indent = line.substring(0, line.length - trimmed.length)
            indent + prefixGenerator(index) + trimmed
        }
    }
    val replacement = formattedLines.joinToString("\n")
    val newText = text.replaceRange(lineStart, lineEnd, replacement)
    val newSelection = TextRange(lineStart, lineStart + replacement.length)
    return value.copy(text = newText, selection = newSelection)
}
