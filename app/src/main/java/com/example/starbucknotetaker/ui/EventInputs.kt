package com.example.starbucknotetaker.ui

import android.location.Address
import android.location.Geocoder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenu
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.Divider
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.DropdownMenuItem
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LocationSuggestion(
    val text: String,
    val display: EventLocationDisplay,
)

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
        Column(modifier = modifier) {
            FormattingToolbar()
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
                singleLine = true,
            )
        }
        return
    }
    val geocoder = remember(context) { Geocoder(context, Locale.getDefault()) }
    var expanded by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<LocationSuggestion>>(emptyList()) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

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
                        ?.mapNotNull { address ->
                            val summary = address.toSuggestion() ?: return@mapNotNull null
                            val fallbackDisplay = fallbackEventLocationDisplay(summary)
                            val resolvedDisplay = address.toEventLocationDisplay()
                                ?.mergeWithFallback(fallbackDisplay)
                                ?: fallbackDisplay
                            LocationSuggestion(
                                text = summary,
                                display = resolvedDisplay,
                            )
                        }
                        ?.distinctBy { it.text }
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }
            suggestions = results
            expanded = suggestions.isNotEmpty()
        }
    }

    Column(modifier = modifier) {
        FormattingToolbar()
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = {
                if (suggestions.isNotEmpty()) {
                    expanded = it
                    if (it) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                } else {
                    expanded = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { newValue ->
                    onValueChange(newValue)
                    requestSuggestions(newValue)
                    keyboardController?.show()
                },
                label = { Text(label) },
                leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            DropdownMenu(
                expanded = expanded && suggestions.isNotEmpty(),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = false),
            ) {
                suggestions.forEach { suggestion ->
                    DropdownMenuItem(onClick = {
                        val formattedSelection = suggestion.display
                            .toQueryString()
                            .ifBlank { suggestion.text }
                        onValueChange(formattedSelection)
                        expanded = false
                        suggestions = emptyList()
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = suggestion.display.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Medium),
                            )
                            suggestion.display.address?.let { address ->
                                Text(
                                    text = address,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.caption,
                                )
                            }
                        }
                    }
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
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    var hasClearedCurrentFocus by remember { mutableStateOf(false) }

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

    LaunchedEffect(zoneId, isFocused) {
        if (!isFocused && zoneId.id != query) {
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
        val showOptions = expanded && filteredZones.isNotEmpty()
        OutlinedTextField(
            value = query,
            onValueChange = { newValue ->
                query = newValue
                expanded = true
                keyboardController?.show()
            },
            label = { Text(label) },
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (filteredZones.isNotEmpty()) {
                            val nextExpanded = !expanded
                            expanded = nextExpanded
                            if (nextExpanded) {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                        }
                    },
                ) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = showOptions)
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused) {
                        if (!hasClearedCurrentFocus && query == zoneId.id) {
                            hasClearedCurrentFocus = true
                            query = ""
                        }
                    } else {
                        expanded = false
                        hasClearedCurrentFocus = false
                    }
                },
        )
        if (showOptions) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                elevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .zIndex(1f),
            ) {
                LazyColumn {
                    itemsIndexed(filteredZones) { index, zone ->
                        ZoneSuggestionRow(
                            text = formatZoneLabel(zone, locale),
                            onClick = {
                                query = zone.id
                                expanded = false
                                onZoneChange(zone)
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            },
                        )
                        if (index < filteredZones.lastIndex) {
                            Divider()
                        }
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

@Composable
private fun ZoneSuggestionRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
    )
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

 
