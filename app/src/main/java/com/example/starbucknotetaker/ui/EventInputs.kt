package com.example.starbucknotetaker.ui

import android.location.Address
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
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
                        ?.mapNotNull(Address::toSuggestion)
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }
            suggestions = results.distinct()
            expanded = suggestions.isNotEmpty()
        }
    }

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
        modifier = modifier,
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
                    onValueChange(suggestion)
                    expanded = false
                    suggestions = emptyList()
                    focusRequester.requestFocus()
                    keyboardController?.show()
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
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

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
            onExpandedChange = { isExpanded ->
                val hasItems = filteredZones.isNotEmpty()
                expanded = isExpanded && hasItems
                if (expanded) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
            },
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { newValue ->
                    query = newValue
                    expanded = true
                    keyboardController?.show()
                },
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && filteredZones.isNotEmpty()) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            DropdownMenu(
                expanded = expanded && filteredZones.isNotEmpty(),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = false),
            ) {
                filteredZones.forEach { zone ->
                    DropdownMenuItem(onClick = {
                        query = zone.id
                        expanded = false
                        onZoneChange(zone)
                        focusRequester.requestFocus()
                        keyboardController?.show()
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

private fun Address.toSuggestion(): String? {
    val components = linkedSetOf<String>()

    fun addIfUseful(value: String?) {
        val cleaned = value?.trim().orEmpty()
        if (cleaned.isNotEmpty()) {
            components.add(cleaned)
        }
    }

    val poiName = listOfNotNull(
        extras?.getString("name"),
        extras?.getString("feature_name"),
        featureName,
        premises,
    ).map { it.trim() }
        .firstOrNull { candidate ->
            candidate.isNotEmpty() &&
                !candidate.equals(thoroughfare?.trim(), ignoreCase = true) &&
                !candidate.equals(subThoroughfare?.trim(), ignoreCase = true) &&
                !candidate.isLikelyHouseNumber()
        }
    addIfUseful(poiName)

    val street = listOfNotNull(thoroughfare?.trim(), subThoroughfare?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(separator = " ")
        .takeIf { it.isNotEmpty() }
    addIfUseful(street)

    val cityLine = buildList {
        val postal = postalCode?.trim()
        if (!postal.isNullOrEmpty()) {
            add(postal)
        }
        val city = locality?.trim()
        if (!city.isNullOrEmpty()) {
            add(city)
        }
    }.joinToString(separator = " ")
        .takeIf { it.isNotEmpty() }
    addIfUseful(cityLine)

    addIfUseful(subLocality?.takeUnless { it.equals(locality, ignoreCase = true) })
    addIfUseful(subAdminArea?.takeUnless { it.equals(locality, ignoreCase = true) })
    addIfUseful(adminArea)
    addIfUseful(countryName)

    val summary = components.joinToString(separator = ", ")
    if (summary.isNotEmpty()) {
        return summary
    }

    val addressLine = getAddressLine(0)?.trim()
    return addressLine.takeUnless { it.isNullOrEmpty() }
}

private fun String.isLikelyHouseNumber(): Boolean {
    val normalized = trim()
    if (normalized.isEmpty()) return false
    if (!normalized.first().isDigit()) return false
    return normalized.length <= 6 && normalized.all { it.isLetterOrDigit() || it == '-' }
}
