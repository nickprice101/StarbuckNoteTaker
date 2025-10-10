package com.example.starbucknotetaker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.example.starbucknotetaker.REMINDER_MINUTE_OPTIONS
import com.example.starbucknotetaker.formatReminderOffsetMinutes

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun ReminderOffsetDropdown(
    selectedMinutes: Int,
    onMinutesSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fieldLabel: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = formatReminderOffsetMinutes(selectedMinutes)

    val density = LocalDensity.current
    var textFieldWidthPx by remember { mutableStateOf(0) }
    val dropdownWidth = textFieldWidthPx.takeIf { it > 0 }?.let { with(density) { it.toDp() } }
    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(fieldLabel) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded,
                    onIconClick = { expanded = !expanded },
                )
            },
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    textFieldWidthPx = coordinates.size.width
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    expanded = true
                },
        )
        val dropdownModifier = dropdownWidth?.let { Modifier.width(it) } ?: Modifier
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = dropdownModifier,
        ) {
            val options = if (REMINDER_MINUTE_OPTIONS.contains(selectedMinutes)) {
                REMINDER_MINUTE_OPTIONS
            } else {
                listOf(selectedMinutes) + REMINDER_MINUTE_OPTIONS
            }
            options.distinct().forEach { minutes ->
                DropdownMenuItem(onClick = {
                    expanded = false
                    onMinutesSelected(minutes)
                }) {
                    Text(formatReminderOffsetMinutes(minutes))
                }
            }
        }
    }
}
