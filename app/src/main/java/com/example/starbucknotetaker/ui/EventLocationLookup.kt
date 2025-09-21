package com.example.starbucknotetaker.ui

import android.location.Geocoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberEventLocationDisplay(location: String?): EventLocationDisplay? {
    val query = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val fallback = remember(query) { EventLocationDisplay(name = query, address = null) }
    val geocoderAvailable = remember { Geocoder.isPresent() }
    if (!geocoderAvailable) {
        return fallback
    }
    val context = LocalContext.current
    val geocoder = remember(context) { Geocoder(context, Locale.getDefault()) }
    val display by produceState(initialValue = fallback, key1 = query, key2 = geocoder) {
        val address = withContext(Dispatchers.IO) {
            runCatching {
                geocoder.getFromLocationName(query, 5)?.firstOrNull()
            }.getOrNull()
        }
        value = address?.toEventLocationDisplay(query) ?: fallback
    }
    return display
}
