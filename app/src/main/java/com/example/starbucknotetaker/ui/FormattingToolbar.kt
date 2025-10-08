package com.example.starbucknotetaker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.IconToggleButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.richtext.RichTextStyle

enum class FormattingAction {
    BulletList,
    NumberedList,
}

@Composable
fun FormattingToolbar(
    visible: Boolean,
    activeStyles: Set<RichTextStyle>,
    onToggleStyle: (RichTextStyle) -> Unit,
    onToggleHighlight: (Color) -> Unit,
    onSelectTextColor: (Color?) -> Unit,
    onAction: (FormattingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showColorDialog by remember { mutableStateOf(false) }
    val highlightStyle = activeStyles.firstOrNull { it is RichTextStyle.Highlight } as? RichTextStyle.Highlight
    val textColorStyle = activeStyles.firstOrNull { it is RichTextStyle.TextColor } as? RichTextStyle.TextColor

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
                ToolbarToggleButton(
                    icon = Icons.Filled.FormatBold,
                    checked = activeStyles.contains(RichTextStyle.Bold),
                    onCheckedChange = { onToggleStyle(RichTextStyle.Bold) },
                )
                ToolbarToggleButton(
                    icon = Icons.Filled.FormatItalic,
                    checked = activeStyles.contains(RichTextStyle.Italic),
                    onCheckedChange = { onToggleStyle(RichTextStyle.Italic) },
                )
                ToolbarToggleButton(
                    icon = Icons.Filled.FormatUnderlined,
                    checked = activeStyles.contains(RichTextStyle.Underline),
                    onCheckedChange = { onToggleStyle(RichTextStyle.Underline) },
                )
                ToolbarToggleButton(
                    icon = Icons.Filled.Highlight,
                    checked = highlightStyle != null,
                    onCheckedChange = {
                        val color = highlightStyle?.color ?: DefaultHighlightColor
                        onToggleHighlight(color)
                    },
                    checkedTint = highlightStyle?.color ?: DefaultHighlightColor,
                )
                ToolbarColorButton(
                    icon = Icons.Filled.FormatColorText,
                    selectedColor = textColorStyle?.color,
                    onClick = { showColorDialog = true },
                )
                ToolbarActionButton(Icons.AutoMirrored.Filled.FormatListBulleted) {
                    onAction(FormattingAction.BulletList)
                }
                ToolbarActionButton(Icons.Filled.FormatListNumbered) {
                    onAction(FormattingAction.NumberedList)
                }
            }
        }
    }

    if (showColorDialog) {
        ColorSelectionDialog(
            selectedColor = textColorStyle?.color,
            onDismiss = { showColorDialog = false },
            onColorSelected = { color ->
                showColorDialog = false
                onSelectTextColor(color)
            },
        )
    }
}

@Composable
private fun ToolbarToggleButton(
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    checkedTint: Color? = null,
) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onCheckedChange() },
        modifier = Modifier.size(44.dp),
    ) {
        val tint = when {
            checked && checkedTint != null -> checkedTint
            checked -> MaterialTheme.colors.primary
            else -> Color.Unspecified
        }
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
    }
}

@Composable
private fun ToolbarColorButton(
    icon: ImageVector,
    selectedColor: Color?,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = selectedColor ?: MaterialTheme.colors.onSurface,
        )
    }
}

@Composable
private fun ToolbarActionButton(
    icon: ImageVector,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ColorSelectionDialog(
    selectedColor: Color?,
    onDismiss: () -> Unit,
    onColorSelected: (Color?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val options = listOf<Color?>(null) + TextColorOptions
                options.chunked(COLOR_GRID_COLUMNS).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { color ->
                            ColorSwatch(
                                color = color,
                                isSelected = if (color == null) {
                                    selectedColor == null
                                } else {
                                    selectedColor == color
                                },
                                onSelect = { onColorSelected(color) },
                            )
                        }
                        if (row.size < COLOR_GRID_COLUMNS) {
                            repeat(COLOR_GRID_COLUMNS - row.size) {
                                Spacer(modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ColorSwatch(
    color: Color?,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colors.primary
    } else {
        MaterialTheme.colors.onSurface.copy(alpha = 0.2f)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color ?: MaterialTheme.colors.surface)
            .border(width = if (isSelected) 2.dp else 1.dp, color = borderColor, shape = CircleShape)
            .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) {
            Icon(
                imageVector = Icons.Filled.FormatColorReset,
                contentDescription = null,
                tint = MaterialTheme.colors.onSurface,
            )
        }
    }
}

private val DefaultHighlightColor = Color(0xFFFFFF8D)

private val TextColorOptions = listOf(
    Color(0xFF000000),
    Color(0xFF37474F),
    Color(0xFFEF5350),
    Color(0xFFFF7043),
    Color(0xFFFFCA28),
    Color(0xFF66BB6A),
    Color(0xFF26A69A),
    Color(0xFF42A5F5),
    Color(0xFF5C6BC0),
    Color(0xFF7E57C2),
    Color(0xFFEC407A),
    Color(0xFF8D6E63),
)

private const val COLOR_GRID_COLUMNS = 4
