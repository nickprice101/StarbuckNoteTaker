package com.example.starbucknotetaker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextFieldDefaults.OutlinedTextFieldDecorationBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.richtext.RichTextStyle
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun RichTextEditor(
    value: RichTextValue,
    onValueChange: (RichTextValue) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    showInlineToolbar: Boolean = true,
    floatingToolbarKey: Long? = null,
    onFloatingToolbarChange: (Long, FloatingFormattingToolbarState?) -> Unit = { _, _ -> },
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = TextFieldDefaults.outlinedTextFieldColors()
    val state = remember { RichTextState(value) }
    val notifyExternalIfChanged = rememberUpdatedState(newValue = onValueChange)
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)

    LaunchedEffect(value) {
        if (value != state.value) {
            state.setExternalValue(value)
        }
    }

    val floatingToolbarState = if (!showInlineToolbar && floatingToolbarKey != null && isFocused) {
        FloatingFormattingToolbarState(
            ownerId = floatingToolbarKey,
            activeStyles = state.activeStyles,
            onToggleStyle = { style ->
                state.toggleInlineStyle(style)
                if (state.value != value) {
                    notifyExternalIfChanged.value(state.value)
                }
            },
            onToggleHighlight = { color ->
                state.toggleHighlight(color)
                if (state.value != value) {
                    notifyExternalIfChanged.value(state.value)
                }
            },
            onSelectTextColor = { color ->
                if (color == null) {
                    state.clearTextColor()
                } else {
                    state.applyTextColor(color)
                }
                if (state.value != value) {
                    notifyExternalIfChanged.value(state.value)
                }
            },
            onAction = { action ->
                state.applyFormattingAction(action)
                notifyExternalIfChanged.value(state.value)
            },
        )
    } else {
        null
    }

    LaunchedEffect(floatingToolbarKey, floatingToolbarState) {
        if (floatingToolbarKey != null) {
            onFloatingToolbarChange(floatingToolbarKey, floatingToolbarState)
        }
    }

    DisposableEffect(floatingToolbarKey) {
        onDispose {
            if (floatingToolbarKey != null) {
                onFloatingToolbarChange(floatingToolbarKey, null)
            }
        }
    }

    val bringCursorIntoView: suspend () -> Unit = inner@{
        val layout = textLayoutResult ?: return@inner
        val annotatedText = layout.layoutInput.text
        val rawText = annotatedText.text
        val selectionEnd = state.value.selection.end.coerceAtLeast(state.value.selection.start)
        val clampedSelection = selectionEnd.coerceIn(0, rawText.length)
        val cursorRect = layout.getCursorRect(clampedSelection)
        val contextStart = findSentenceContextStart(rawText, clampedSelection)
        val contextLine = layout.getLineForOffset(contextStart)
        val contextTop = layout.getLineTop(contextLine)
        val cursorLine = layout.getLineForOffset(clampedSelection)
        val cursorBottom = layout.getLineBottom(cursorLine)
        val cursorTop = layout.getLineTop(cursorLine)
        val lineHeight = cursorBottom - cursorTop
        val expandedBottom = min(
            layout.size.height.toFloat(),
            max(cursorRect.bottom, cursorBottom) + lineHeight,
        )
        val rect = Rect(
            left = 0f,
            top = min(contextTop, cursorRect.top),
            right = layout.size.width.toFloat(),
            bottom = max(expandedBottom, cursorRect.bottom),
        )
        bringIntoViewRequester.bringIntoView(rect)
    }

    LaunchedEffect(isFocused, state.value.selection, textLayoutResult, imeBottom) {
        if (isFocused) {
            bringCursorIntoView()
        }
    }

    BasicTextField(
        value = state.asTextFieldValue(),
        onValueChange = { newValue ->
            state.updateFromTextField(newValue)
            onValueChange(state.value)
        },
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        bringCursorIntoView()
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    val handled = state.handleEnterKey()
                    if (handled) {
                        onValueChange(state.value)
                    }
                    handled
                } else {
                    false
                }
            },
        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colors.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colors.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interactionSource,
        onTextLayout = { layoutResult ->
            textLayoutResult = layoutResult
        },
        decorationBox = { innerTextField ->
            OutlinedTextFieldDecorationBox(
                value = state.value.text,
                visualTransformation = VisualTransformation.None,
                innerTextField = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        FormattingToolbar(
                            visible = showInlineToolbar && isFocused,
                            activeStyles = state.activeStyles,
                            onToggleStyle = { style: RichTextStyle ->
                                state.toggleInlineStyle(style)
                                if (state.value != value) {
                                    onValueChange(state.value)
                                }
                            },
                            onToggleHighlight = { color ->
                                state.toggleHighlight(color)
                                if (state.value != value) {
                                    onValueChange(state.value)
                                }
                            },
                            onSelectTextColor = { color ->
                                if (color == null) {
                                    state.clearTextColor()
                                } else {
                                    state.applyTextColor(color)
                                }
                                if (state.value != value) {
                                    onValueChange(state.value)
                                }
                            },
                            onAction = { action ->
                                state.applyFormattingAction(action)
                                onValueChange(state.value)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        innerTextField()
                    }
                },
                placeholder = null,
                singleLine = false,
                enabled = true,
                isError = false,
                interactionSource = interactionSource,
                label = label,
                leadingIcon = null,
                trailingIcon = null,
                colors = colors,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (showInlineToolbar && isFocused) 0.dp else 16.dp,
                    bottom = 16.dp,
                ),
            )
        },
    )
}

data class FloatingFormattingToolbarState(
    val ownerId: Long,
    val activeStyles: Set<RichTextStyle>,
    val onToggleStyle: (RichTextStyle) -> Unit,
    val onToggleHighlight: (Color) -> Unit,
    val onSelectTextColor: (Color?) -> Unit,
    val onAction: (FormattingAction) -> Unit,
)

private fun findSentenceContextStart(text: String, selection: Int): Int {
    if (text.isEmpty()) {
        return 0
    }
    val clampedSelection = selection.coerceIn(0, text.length)
    val currentSentenceStart = findSentenceStart(text, clampedSelection)
    if (currentSentenceStart <= 0) {
        return 0
    }
    val previousSentenceStart = findSentenceStart(text, currentSentenceStart - 1)
    return if (previousSentenceStart < currentSentenceStart) {
        previousSentenceStart.coerceIn(0, text.length)
    } else {
        currentSentenceStart
    }
}

private fun findSentenceStart(text: String, index: Int): Int {
    if (text.isEmpty()) {
        return 0
    }
    var searchIndex = index.coerceIn(0, text.length)
    if (searchIndex == text.length && text.isNotEmpty()) {
        searchIndex--
    }
    while (searchIndex >= 0 && text[searchIndex].isSkippableWhitespace()) {
        searchIndex--
    }
    if (searchIndex < 0) {
        return 0
    }
    if (text[searchIndex].isSentenceTerminator()) {
        return skipForwardWhitespace(text, searchIndex + 1)
    }
    for (i in searchIndex downTo 0) {
        val ch = text[i]
        if (ch.isSentenceTerminator()) {
            return skipForwardWhitespace(text, i + 1)
        }
    }
    return 0
}

private fun skipForwardWhitespace(text: String, start: Int): Int {
    var idx = start
    while (idx < text.length && text[idx].isWhitespace()) {
        idx++
    }
    return idx.coerceIn(0, text.length)
}

private fun Char.isSentenceTerminator(): Boolean {
    return this == '.' || this == '!' || this == '?' || this == '\n' || this == '\r'
}

private fun Char.isSkippableWhitespace(): Boolean {
    return this != '\n' && this != '\r' && this.isWhitespace()
}
