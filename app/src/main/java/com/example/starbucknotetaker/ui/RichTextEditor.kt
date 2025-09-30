package com.example.starbucknotetaker.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.MaterialTheme
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.LocalTextStyle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.TextFieldDefaults.OutlinedTextFieldDecorationBox
import androidx.compose.foundation.layout.PaddingValues

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun RichTextEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    onAction: (FormattingAction) -> Unit,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = TextFieldDefaults.outlinedTextFieldColors()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colors.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colors.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDecorationBox(
                value = value.text,
                visualTransformation = VisualTransformation.None,
                innerTextField = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        FormattingToolbar(
                            visible = isFocused,
                            onAction = onAction,
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
                    top = if (isFocused) 52.dp else 24.dp,
                    bottom = 16.dp,
                ),
            )
        },
    )
}
