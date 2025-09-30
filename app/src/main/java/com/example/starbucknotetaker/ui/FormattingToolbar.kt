package com.example.starbucknotetaker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconToggleButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.richtext.RichTextStyle

@Composable
fun FormattingToolbar(
    visible: Boolean,
    activeStyles: Set<RichTextStyle>,
    onToggle: (RichTextStyle) -> Unit,
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
                ToolbarToggleButton(
                    icon = Icons.Filled.FormatBold,
                    checked = activeStyles.contains(RichTextStyle.Bold),
                    onCheckedChange = { onToggle(RichTextStyle.Bold) },
                )
                ToolbarToggleButton(
                    icon = Icons.Filled.FormatItalic,
                    checked = activeStyles.contains(RichTextStyle.Italic),
                    onCheckedChange = { onToggle(RichTextStyle.Italic) },
                )
                ToolbarToggleButton(
                    icon = Icons.Filled.FormatUnderlined,
                    checked = activeStyles.contains(RichTextStyle.Underline),
                    onCheckedChange = { onToggle(RichTextStyle.Underline) },
                )
            }
        }
    }
}

@Composable
private fun ToolbarToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onCheckedChange() },
        modifier = Modifier.size(44.dp),
    ) {
        val tint = if (checked) MaterialTheme.colors.primary else Color.Unspecified
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
    }
}
