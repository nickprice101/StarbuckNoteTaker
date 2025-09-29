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
    val fallback = remember(query) { fallbackEventLocationDisplay(query) }
    val geocoderAvailable = remember { Geocoder.isPresent() }
    if (!geocoderAvailable) {
        return fallback
    }
    val context = LocalContext.current
    val geocoder = remember(context) { Geocoder(context, Locale.getDefault()) }
    val display by produceState(initialValue = fallback, key1 = query, key2 = geocoder) {
        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                // First try the enhanced venue lookup with the original query
                val venueResult = lookupVenueAtAddress(geocoder, query)
                if (venueResult != null) {
                    listOf(venueResult)
                } else {
                    // Fallback to standard geocoding
                    geocoder.getFromLocationName(query, 5)
                        ?.mapNotNull { candidate ->
                            candidate.toEventLocationDisplay()
                        }
                }
            }.getOrNull()
        }.orEmpty()
        
        val prioritized = resolved.firstOrNull { candidate ->
            !candidate.name.equals(fallback.name, ignoreCase = true) ||
                candidate.address != null
        }
        val best = prioritized ?: resolved.firstOrNull()
        value = best?.mergeWithFallback(fallback) ?: fallback
    }
    return display
}
