package com.example.starbucknotetaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
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
import androidx.compose.ui.unit.dp

@Composable
fun FormattingToolbar(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colors.surface,
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            val icons = listOf(
                Icons.Filled.FormatBold,
                Icons.Filled.FormatItalic,
                Icons.Filled.FormatUnderlined,
                Icons.Filled.FormatSize,
                Icons.Filled.FormatColorText,
                Icons.Filled.Highlight,
                Icons.Filled.FormatListBulleted,
                Icons.Filled.FormatListNumbered,
            )
            icons.forEach { icon ->
                IconButton(onClick = {}) {
                    Icon(imageVector = icon, contentDescription = null)
                }
            }
        }
    }
    Divider()
}
