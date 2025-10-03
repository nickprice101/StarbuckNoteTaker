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
        
        val result = withContext(Dispatchers.IO) {
            // Step 1: Always check if user input is a venue name first
            val userVenueName = extractAndFormatVenueName(query)

            if (userVenueName != null) {
                Log.d(
                    "EventLocationLookup",
                    "Detected venue name '$userVenueName' from query '$query'"
                )

                // Step 2: Get address information via geocoding
                val addresses = runCatching {
                    geocoder.getFromLocationName(query, 5) ?: emptyList()
                }.getOrElse { emptyList() }
                
                Log.d("EventLocationLookup", "Found ${addresses.size} geocoding results")
                
                if (addresses.isNotEmpty()) {
                    // Step 3: Look for a more complete venue name in the geocoding results
                    val enhancedVenueName = findEnhancedVenueName(userVenueName, addresses)
                    val finalVenueName = enhancedVenueName ?: userVenueName

                    // Step 4: Build the address string
                    val addressString = buildFullAddress(addresses.first())

                    Log.d("EventLocationLookup", "Final venue name: '$finalVenueName'")
                    Log.d("EventLocationLookup", "Address: '$addressString'")

                    val venueDisplay = EventLocationDisplay(
                        name = finalVenueName,
                        address = addressString.takeIf { it.isNotEmpty() }
                    )
                    Log.d("EventLocationLookup", "Venue branch display result: $venueDisplay")
                    venueDisplay
                } else {
                    // No geocoding results, but we still have the venue name
                    val venueDisplay = EventLocationDisplay(name = userVenueName, address = null)
                    Log.d("EventLocationLookup", "Venue branch display result: $venueDisplay")
                    venueDisplay
                }
            } else {
                // User input doesn't look like a venue name, use standard processing
                if (query.contains("melkweg", ignoreCase = true)) {
                    Log.d(
                        "EventLocationLookup",
                        "No venue detected for query '$query'; contains 'melkweg', treating as address"
                    )
                } else {
                    Log.d(
                        "EventLocationLookup",
                        "No venue detected for query '$query'; treating as address"
                    )
                }
                val venueResult = runCatching {
                    lookupVenueAtAddress(geocoder, query)
                }.getOrNull()

                if (venueResult != null) {
                    Log.d(
                        "EventLocationLookup",
                        "lookupVenueAtAddress returned venue display: $venueResult"
                    )
                } else {
                    Log.d(
                        "EventLocationLookup",
                        "lookupVenueAtAddress returned null; falling back to name='${fallback.name}', address='${fallback.address}'"
                    )
                }

                venueResult ?: fallback
            }
        }
        
        value = result
    }
    
    return display
}

/**
 * Extracts and properly formats a venue name from user input
 */
private fun extractAndFormatVenueName(query: String): String? {
    val trimmed = query.trim()
    
    // Split by common separators
    val parts = trimmed.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    
    val firstPart = parts[0]
    
    // Check if this looks like a venue name
    if (isVenueName(firstPart)) {
        return formatVenueName(firstPart)
    }
    
    // If it's a single input without separators, check if it's a venue name
    if (parts.size == 1 && isVenueName(trimmed)) {
        return formatVenueName(trimmed)
    }
    
    return null
}

/**
 * Determines if a string looks like a venue name
 */
private fun isVenueName(text: String): Boolean {
    val trimmed = text.trim()
    
    // Basic checks
    if (trimmed.length < 2) return false
    if (trimmed.matches(Regex("^\\d+.*"))) return false // Starts with number
    if (trimmed.matches(Regex("^\\d+$"))) return false // Only numbers
    
    // Check for address keywords that indicate it's NOT a venue name
    val addressKeywords = listOf(
        "street", "st", "avenue", "ave", "road", "rd", "lane", "ln",
        "drive", "dr", "boulevard", "blvd", "straat", "gracht", 
        "plein", "singel", "kade", "way", "place", "pl", "court", "ct"
    )
    
    val hasAddressKeyword = addressKeywords.any { keyword ->
        trimmed.contains("\\b$keyword\\b".toRegex(RegexOption.IGNORE_CASE))
    }
    
    if (hasAddressKeyword) return false
    
    // Check for postal code patterns
    if (trimmed.matches(Regex("\\d{4,5}\\s*[A-Z]{0,2}"))) return false
    
    return true
}

/**
 * Formats a venue name with proper capitalization
 */
private fun formatVenueName(venueName: String): String {
    return venueName.split(" ").joinToString(" ") { word ->
        if (word.isEmpty()) {
            word
        } else {
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }
}

/**
 * Looks for a more complete venue name in geocoding results
 * For example, if user typed "Paard Van" and geocoding found "Paard Van Marken", return the complete name
 */
private fun findEnhancedVenueName(userVenueName: String, addresses: List<android.location.Address>): String? {
    val userLower = userVenueName.lowercase().trim()
    
    for (address in addresses) {
        // Check various sources for venue names
        val candidates = listOfNotNull(
            address.featureName,
            address.premises,
            address.extras?.getString("name"),
            address.extras?.getString("establishment"),
            address.extras?.getString("point_of_interest"),
            address.extras?.getString("place_name"),
            // Also check the address line for venue names
            address.getAddressLine(0)?.let { line ->
                // Extract potential venue name from address line
                extractVenueFromAddressLine(line, userVenueName)
            }
        )
        
        for (candidate in candidates) {
            val candidateLower = candidate.lowercase().trim()
            
            // Check if the candidate contains the user's input as a prefix or partial match
            if (candidateLower.startsWith(userLower) || 
                candidateLower.contains(userLower)) {
                
                // Make sure it's not just a street address
                if (isVenueName(candidate)) {
                    Log.d("EventLocationLookup", "Found enhanced venue name: '$candidate' for user input: '$userVenueName'")
                    return formatVenueName(candidate)
                }
            }
        }
    }
    
    return null
}

/**
 * Extracts venue name from an address line if it contains the user's input
 */
private fun extractVenueFromAddressLine(addressLine: String, userInput: String): String? {
    val userLower = userInput.lowercase().trim()
    val lineLower = addressLine.lowercase()
    
    if (!lineLower.contains(userLower)) return null
    
    // Split the address line by commas and look for the part containing the user input
    val parts = addressLine.split(',').map { it.trim() }
    
    for (part in parts) {
        if (part.lowercase().contains(userLower) && isVenueName(part)) {
            return part.trim()
        }
    }
    
    return null
}

/**
 * Builds a complete address string from geocoding results
 */
private fun buildFullAddress(address: android.location.Address): String {
    val parts = mutableListOf<String>()
    
    // Street address
    val streetParts = mutableListOf<String>()
    address.subThoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { streetParts.add(it) }
    address.thoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { streetParts.add(it) }
    if (streetParts.isNotEmpty()) {
        parts.add(streetParts.joinToString(" "))
    }
    
    // Postal code and city
    val cityParts = mutableListOf<String>()
    address.postalCode?.trim()?.takeIf { it.isNotEmpty() }?.let { cityParts.add(it) }
    address.locality?.trim()?.takeIf { it.isNotEmpty() }?.let { cityParts.add(it) }
    if (cityParts.isNotEmpty()) {
        parts.add(cityParts.joinToString(" "))
    }
    
    // Sub-admin area (if different from locality)
    address.subAdminArea?.trim()?.takeIf { 
        it.isNotEmpty() && !it.equals(address.locality?.trim(), ignoreCase = true) 
    }?.let { parts.add(it) }
    
    // Admin area (state/province)
    address.adminArea?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    
    // Country
    address.countryName?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    
    return parts.joinToString(", ")
}
