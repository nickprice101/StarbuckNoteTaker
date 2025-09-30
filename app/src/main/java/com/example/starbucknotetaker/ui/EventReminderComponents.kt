package com.example.starbucknotetaker.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(fieldLabel) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = modifier.fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
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
