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
                // FIRST: Check if user input looks like a venue name we should preserve
                val preservedVenueName = extractAndCapitalizeVenueName(query)
                
                if (preservedVenueName != null) {
                    // User entered a venue name - preserve it and get geocoded address
                    val addresses = geocoder.getFromLocationName(query, 1) ?: emptyList()
                    val geocodedAddress = addresses.firstOrNull()
                    
                    if (geocodedAddress != null) {
                        val fullAddress = buildCompleteAddress(geocodedAddress)
                        EventLocationDisplay(
                            name = preservedVenueName,
                            address = fullAddress.takeIf { it.isNotEmpty() }
                        )
                    } else {
                        EventLocationDisplay(name = preservedVenueName, address = null)
                    }
                } else {
                    // Try enhanced venue lookup for complex queries
                    lookupVenueAtAddress(geocoder, query) ?: run {
                        // Fallback to standard geocoding
                        geocoder.getFromLocationName(query, 5)
                            ?.mapNotNull { candidate ->
                                candidate.toEventLocationDisplay()
                            }
                            ?.firstOrNull()
                    }
                }
            }.getOrNull()
        }
        
        value = resolved ?: fallback
    }
    return display
}

/**
 * Extracts and properly capitalizes a venue name from user input
 */
private fun extractAndCapitalizeVenueName(query: String): String? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null
    
    // Split by common separators to get the first part
    val parts = trimmed.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    
    val firstPart = parts[0]
    
    // Check if this looks like a venue name
    if (isVenueName(firstPart)) {
        // Capitalize each word properly
        return capitalizeVenueName(firstPart)
    }
    
    // If it's a single term without separators, check if the whole thing is a venue
    if (parts.size == 1 && isVenueName(trimmed)) {
        return capitalizeVenueName(trimmed)
    }
    
    return null
}

/**
 * Determines if text looks like a venue name rather than an address
 */
private fun isVenueName(text: String): Boolean {
    val trimmed = text.trim().lowercase()
    
    // Basic length check
    if (trimmed.length < 2 || trimmed.length > 50) return false
    
    // Must not start with numbers (street addresses do)
    if (trimmed.matches(Regex("^\\d+.*"))) return false
    
    // Must not be purely numeric
    if (trimmed.matches(Regex("^\\d+$"))) return false
    
    // Must not contain street/address keywords
    val streetKeywords = setOf(
        "street", "st", "avenue", "ave", "road", "rd", "lane", "ln",
        "drive", "dr", "boulevard", "blvd", "way", "place", "pl",
        "court", "ct", "circle", "cir", "straat", "gracht", "plein",
        "singel", "kade", "laan", "weg"
    )
    
    // Check if any street keyword appears as a whole word
    val words = trimmed.split(Regex("\\s+"))
    if (words.any { word -> streetKeywords.contains(word) }) {
        return false
    }
    
    // Must not be postal code pattern
    if (trimmed.matches(Regex("\\d{4,5}\\s*[a-z]{0,2}"))) return false
    
    // Must not contain obvious address patterns
    if (trimmed.contains(Regex("\\d+\\s+\\w+"))) return false
    
    return true
}

/**
 * Properly capitalizes a venue name
 */
private fun capitalizeVenueName(name: String): String {
    return name.split(Regex("\\s+"))
        .joinToString(" ") { word ->
            if (word.isNotEmpty()) {
                word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            } else {
                word
            }
        }
}

private fun buildCompleteAddress(address: android.location.Address): String {
    val components = mutableListOf<String>()
    
    // Street address (number + street name)
    val streetAddress = buildList {
        address.subThoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        address.thoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(" ").takeIf { it.isNotEmpty() }
    
    streetAddress?.let { components.add(it) }
    
    // Postal code and city
    val cityInfo = buildList {
        address.postalCode?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        address.locality?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(" ").takeIf { it.isNotEmpty() }
    
    cityInfo?.let { components.add(it) }
    
    // Sub-administrative area (if different from locality)
    address.subAdminArea?.trim()?.let { subAdmin ->
        if (subAdmin.isNotEmpty() && !subAdmin.equals(address.locality?.trim(), ignoreCase = true)) {
            components.add(subAdmin)
        }
    }
    
    // Administrative area (state/province)
    address.adminArea?.trim()?.takeIf { it.isNotEmpty() }?.let { components.add(it) }
    
    // Country
    address.countryName?.trim()?.takeIf { it.isNotEmpty() }?.let { components.add(it) }
    
    return components.joinToString(", ")
}
