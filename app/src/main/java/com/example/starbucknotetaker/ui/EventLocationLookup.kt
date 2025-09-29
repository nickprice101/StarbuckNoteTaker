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
        val result = withContext(Dispatchers.IO) {
            runCatching {
                // Check if the original query looks like a venue name
                val originalVenueName = extractVenueNameFromQuery(query)
                
                // Get geocoded address information
                val addresses = geocoder.getFromLocationName(query, 5) ?: emptyList()
                
                if (originalVenueName != null && addresses.isNotEmpty()) {
                    // Use the original venue name with the best geocoded address
                    val bestAddress = addresses.first()
                    val fullAddress = buildAddressFromGeocoded(bestAddress)
                    
                    EventLocationDisplay(
                        name = originalVenueName,
                        address = fullAddress.takeIf { it.isNotEmpty() }
                    )
                } else {
                    // Try to find venue names in geocoded results
                    val venueResult = lookupVenueAtAddress(geocoder, query)
                    if (venueResult != null) {
                        venueResult
                    } else {
                        // Fallback to standard geocoding
                        addresses.mapNotNull { it.toEventLocationDisplay() }.firstOrNull()
                    }
                }
            }.getOrNull()
        }
        
        value = result ?: fallback
    }
    return display
}

/**
 * Extracts a venue name from the user's query if it looks like one
 */
private fun extractVenueNameFromQuery(query: String): String? {
    val trimmed = query.trim()
    
    // Split by common separators and get the first part
    val parts = trimmed.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    val firstPart = parts.firstOrNull() ?: return null
    
    // Check if the first part looks like a venue name
    return if (isVenueNameLike(firstPart)) {
        firstPart
    } else {
        // If the entire query is a single word/phrase and looks like a venue, use it
        if (parts.size == 1 && isVenueNameLike(trimmed)) {
            trimmed
        } else {
            null
        }
    }
}

/**
 * Determines if a string looks like a venue name rather than an address component
 */
private fun isVenueNameLike(text: String): Boolean {
    val trimmed = text.trim()
    
    // Must be at least 2 characters
    if (trimmed.length < 2) return false
    
    // Must not be purely numeric (street numbers)
    if (trimmed.matches(Regex("^\\d+[A-Za-z]*$"))) return false
    
    // Must not start with a number followed by a space (street addresses)
    if (trimmed.matches(Regex("^\\d+\\s+.*"))) return false
    
    // Must not contain obvious address keywords
    val addressKeywords = listOf(
        "street", "st", "avenue", "ave", "road", "rd", "lane", "ln", 
        "drive", "dr", "boulevard", "blvd", "straat", "gracht", 
        "plein", "singel", "kade", "way", "place", "pl", "court", "ct"
    )
    
    if (addressKeywords.any { keyword -> 
        trimmed.contains(keyword, ignoreCase = true) 
    }) return false
    
    // Must not be a postal code pattern
    if (trimmed.matches(Regex("\\b\\d{4,5}\\s*[A-Z]{0,2}\\b"))) return false
    
    // Looks like a venue name
    return true
}

private fun buildAddressFromGeocoded(address: android.location.Address): String {
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
