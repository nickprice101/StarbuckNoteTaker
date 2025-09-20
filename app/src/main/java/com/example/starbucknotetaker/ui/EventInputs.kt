package com.example.starbucknotetaker.ui

import android.location.Geocoder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun LocationAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val geocoderAvailable = remember { Geocoder.isPresent() }
    if (!geocoderAvailable) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = modifier,
            leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
            singleLine = true,
        )
        return
    }
    val geocoder = remember(context) { Geocoder(context, Locale.getDefault()) }
    var expanded by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }

    fun requestSuggestions(query: String) {
        fetchJob?.cancel()
        if (query.length < 3) {
            suggestions = emptyList()
            expanded = false
            return
        }
        fetchJob = coroutineScope.launch {
            delay(300)
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    geocoder.getFromLocationName(query, 5)
                        ?.mapNotNull { address -> address.getAddressLine(0) }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }
            suggestions = results.distinct()
            expanded = suggestions.isNotEmpty()
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it && suggestions.isNotEmpty() },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { newValue ->
                onValueChange(newValue)
                requestSuggestions(newValue)
            },
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded && suggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(onClick = {
                    onValueChange(suggestion)
                    expanded = false
                    suggestions = emptyList()
                }) {
                    Text(text = suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun TimeZonePicker(
    zoneId: ZoneId,
    onZoneChange: (ZoneId) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Time zone",
) {
    val locale = remember { Locale.getDefault() }
    val allZones = remember {
        ZoneId.getAvailableZoneIds()
            .map { ZoneId.of(it) }
            .sortedBy { it.id }
    }
    var expanded by remember { mutableStateOf(false) }
    var query by remember(zoneId) { mutableStateOf(zoneId.id) }

    val filteredZones = remember(query, locale) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            allZones.take(12)
        } else {
            val lowered = normalized.lowercase(locale)
            allZones.filter { zone ->
                zoneSearchStrings(zone, locale).any { candidate ->
                    candidate.lowercase(locale).contains(lowered)
                }
            }.take(12)
        }
    }

    LaunchedEffect(zoneId) {
        if (zoneId.id != query) {
            query = zoneId.id
        }
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@LaunchedEffect
        }
        val lowered = trimmed.lowercase(locale)
        val match = allZones.firstOrNull { zone ->
            zoneSearchStrings(zone, locale).any { candidate ->
                val normalizedCandidate = candidate.replace('_', ' ')
                normalizedCandidate.equals(trimmed, ignoreCase = true) ||
                    normalizedCandidate.lowercase(locale) == lowered
            }
        }
        if (match != null && match != zoneId) {
            onZoneChange(match)
        }
    }

    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded && filteredZones.isNotEmpty(),
            onExpandedChange = { expanded = it && filteredZones.isNotEmpty() },
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { newValue ->
                    query = newValue
                    expanded = true
                },
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && filteredZones.isNotEmpty()) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth(),
            )
            DropdownMenu(
                expanded = expanded && filteredZones.isNotEmpty(),
                onDismissRequest = { expanded = false },
            ) {
                filteredZones.forEach { zone ->
                    DropdownMenuItem(onClick = {
                        query = zone.id
                        expanded = false
                        onZoneChange(zone)
                    }) {
                        Text(text = formatZoneLabel(zone, locale), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Text(
            text = formatZoneLabel(zoneId, locale),
            style = MaterialTheme.typography.caption,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun formatZoneLabel(zoneId: ZoneId, locale: Locale): String {
    val code = formatZoneCode(zoneId, locale)
    val displayName = zoneIdDisplayName(zoneId)
    val longName = zoneId.getDisplayName(TextStyle.FULL, locale)
    val suffix = longName.takeIf { it.isNotBlank() && !it.equals(displayName, ignoreCase = true) }
    return buildString {
        append(code)
        append(" • ")
        append(displayName)
        suffix?.let {
            append(" (")
            append(it)
            append(')')
        }
    }
}
