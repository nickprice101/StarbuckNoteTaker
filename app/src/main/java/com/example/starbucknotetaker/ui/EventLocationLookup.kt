package com.example.starbucknotetaker.ui

import android.location.Geocoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.util.Log
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
        
        Log.d("EventLocationLookup", "Processing query: '$query'")
        Log.d("EventLocationLookup", "Fallback result: name='${fallback.name}', address='${fallback.address}'")
        
        // FIRST: Check if the user input looks like a venue name
        val userVenueName = extractUserVenueName(query)
        Log.d("EventLocationLookup", "Extracted user venue name: '$userVenueName'")
        
        if (userVenueName != null) {
            // User entered what looks like a venue name - preserve it at all costs
            val geocodedAddress = withContext(Dispatchers.IO) {
                runCatching {
                    geocoder.getFromLocationName(query, 1)?.firstOrNull()
                }.getOrNull()
            }
            
            if (geocodedAddress != null) {
                val fullAddress = buildAddressString(geocodedAddress)
                val result = EventLocationDisplay(
                    name = userVenueName, // Always use the user's venue name
                    address = fullAddress.takeIf { it.isNotEmpty() }
                )
                Log.d("EventLocationLookup", "Using user venue name with geocoded address: name='${result.name}', address='${result.address}'")
                value = result
            } else {
                // No geocoding available, but we still preserve the venue name
                val result = EventLocationDisplay(name = userVenueName, address = null)
                Log.d("EventLocationLookup", "Using user venue name without address: name='${result.name}'")
                value = result
            }
        } else {
            // User input doesn't look like a venue name, use normal geocoding
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    lookupVenueAtAddress(geocoder, query)
                }.getOrNull()
            }
            
            if (result != null) {
                Log.d("EventLocationLookup", "Using venue lookup result: name='${result.name}', address='${result.address}'")
                value = result
            } else {
                Log.d("EventLocationLookup", "Using fallback: name='${fallback.name}', address='${fallback.address}'")
                value = fallback
            }
        }
    }
    return display
}

/**
 * Extracts a venue name from user input if it looks like one
 * This is the key function that determines if we should preserve the user's input
 */
private fun extractUserVenueName(query: String): String? {
    val trimmed = query.trim()
    
    // Handle multi-part input (separated by commas or newlines)
    val parts = trimmed.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    
    if (parts.isEmpty()) return null
    
    val firstPart = parts[0]
    
    // Check if the first part looks like a venue name
    if (looksLikeVenueName(firstPart)) {
        return firstPart
    }
    
    // If it's a single word/phrase without separators, and it looks like a venue, use it
    if (parts.size == 1 && looksLikeVenueName(trimmed)) {
        return trimmed
    }
    
    return null
}

/**
 * Determines if a string looks like a venue name
 */
private fun looksLikeVenueName(text: String): Boolean {
    val trimmed = text.trim()
    
    // Must have reasonable length
    if (trimmed.length < 2 || trimmed.length > 50) return false
    
    // Must not be purely numeric
    if (trimmed.matches(Regex("^\\d+$"))) return false
    
    // Must not start with a number (street addresses typically do)
    if (trimmed.matches(Regex("^\\d+.*"))) return false
    
    // Must not contain street/address keywords
    val addressKeywords = listOf(
        "street", "st\\b", "avenue", "ave\\b", "road", "rd\\b", "lane", "ln\\b",
        "drive", "dr\\b", "boulevard", "blvd", "straat", "gracht", "plein", 
        "singel", "kade", "way", "place", "pl\\b", "court", "ct\\b"
    )
    
    val addressPattern = addressKeywords.joinToString("|") { "\\b$it\\b" }
    if (trimmed.matches(Regex(".*($addressPattern).*", RegexOption.IGNORE_CASE))) {
        return false
    }
    
    // Must not be a postal code
    if (trimmed.matches(Regex("\\d{4,5}\\s*[A-Z]{0,2}"))) return false
    
    // Looks like a venue name
    return true
}

private fun buildAddressString(address: android.location.Address): String {
    val parts = mutableListOf<String>()
    
    // Street address
    val streetParts = mutableListOf<String>()
    address.subThoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { streetParts.add(it) }
    address.thoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { streetParts.add(it) }
    if (streetParts.isNotEmpty()) {
        parts.add(streetParts.joinToString(" "))
    }
    
    // City with postal code
    val cityParts = mutableListOf<String>()
    address.postalCode?.trim()?.takeIf { it.isNotEmpty() }?.let { cityParts.add(it) }
    address.locality?.trim()?.takeIf { it.isNotEmpty() }?.let { cityParts.add(it) }
    if (cityParts.isNotEmpty()) {
        parts.add(cityParts.joinToString(" "))
    }
    
    // Region
    address.subAdminArea?.trim()?.takeIf { 
        it.isNotEmpty() && !it.equals(address.locality?.trim(), ignoreCase = true) 
    }?.let { parts.add(it) }
    
    // State/Province
    address.adminArea?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    
    // Country
    address.countryName?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    
    return parts.joinToString(", ")
}
