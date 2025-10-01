package com.example.starbucknotetaker.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextFieldDefaults.OutlinedTextFieldDecorationBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.richtext.RichTextStyle

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun RichTextEditor(
    value: RichTextValue,
    onValueChange: (RichTextValue) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = TextFieldDefaults.outlinedTextFieldColors()
    val state = remember { RichTextState(value) }

    LaunchedEffect(value) {
        if (value != state.value) {
            state.setExternalValue(value)
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
        decorationBox = { innerTextField ->
            OutlinedTextFieldDecorationBox(
                value = state.value.text,
                visualTransformation = VisualTransformation.None,
                innerTextField = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        FormattingToolbar(
                            visible = isFocused,
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
                    top = if (isFocused) 0.dp else 16.dp,
                    bottom = 16.dp,
                ),
            )
        },
    )
}
