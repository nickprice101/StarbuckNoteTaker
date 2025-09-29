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
                // Try the enhanced venue lookup first
                val venueResult = lookupVenueAtAddress(geocoder, query)
                if (venueResult != null) {
                    return@runCatching listOf(venueResult)
                }
                
                // If venue lookup didn't work, try standard geocoding
                val addresses = geocoder.getFromLocationName(query, 5) ?: emptyList()
                val geocodedResults = addresses.mapNotNull { candidate ->
                    candidate.toEventLocationDisplay()
                }
                
                // If we got geocoded results but none are good matches, and our fallback 
                // has a likely venue name, prefer the fallback
                if (geocodedResults.isNotEmpty()) {
                    geocodedResults
                } else {
                    // No good geocoding results, stick with fallback
                    emptyList()
                }
            }.getOrNull()
        }.orEmpty()
        
        if (resolved.isNotEmpty()) {
            // We got some enhanced results, find the best one
            val prioritized = resolved.firstOrNull { candidate ->
                // Prefer results that don't just match the fallback name exactly
                !candidate.name.equals(fallback.name, ignoreCase = true) ||
                    candidate.address != null
            }
            val best = prioritized ?: resolved.firstOrNull()
            value = best?.mergeWithFallback(fallback) ?: fallback
        } else {
            // No enhanced results, but let's check if our fallback has a good venue name
            // If the fallback identified a likely venue name, keep it as-is
            if (fallback.name.isNotEmpty() && fallback.name.length > 2 && 
                !fallback.name.matches(Regex("^\\d+.*")) && // Not starting with numbers
                !fallback.name.contains(Regex("\\b(Street|St|Avenue|Ave|Road|Rd|Lane|Ln|Drive|Dr|Boulevard|Blvd|Straat|Gracht)\\b", RegexOption.IGNORE_CASE))) {
                
                // Try to get a better address by geocoding the query
                val addressOnly = withContext(Dispatchers.IO) {
                    runCatching {
                        geocoder.getFromLocationName(query, 1)?.firstOrNull()?.let { address ->
                            buildFullAddressFromGeocoded(address)
                        }
                    }.getOrNull()
                }
                
                value = if (addressOnly != null) {
                    EventLocationDisplay(
                        name = fallback.name,
                        address = addressOnly.takeIf { !it.equals(fallback.name, ignoreCase = true) }
                    )
                } else {
                    fallback
                }
            } else {
                value = fallback
            }
        }
    }
    return display
}

private fun buildFullAddressFromGeocoded(address: android.location.Address): String {
    val parts = mutableListOf<String>()
    
    // Build street address
    val streetParts = mutableListOf<String>()
    address.subThoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { streetParts.add(it) }
    address.thoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { streetParts.add(it) }
    if (streetParts.isNotEmpty()) {
        parts.add(streetParts.joinToString(" "))
    }
    
    // Add postal code and city
    val cityParts = mutableListOf<String>()
    address.postalCode?.trim()?.takeIf { it.isNotEmpty() }?.let { cityParts.add(it) }
    address.locality?.trim()?.takeIf { it.isNotEmpty() }?.let { cityParts.add(it) }
    if (cityParts.isNotEmpty()) {
        parts.add(cityParts.joinToString(" "))
    }
    
    // Add region info
    address.subAdminArea?.trim()?.takeIf { 
        it.isNotEmpty() && !it.equals(address.locality?.trim(), ignoreCase = true) 
    }?.let { parts.add(it) }
    
    address.adminArea?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    address.countryName?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    
    return parts.joinToString(", ")
}
