package com.example.starbucknotetaker.ui

import android.location.Geocoder
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

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
        
        // Check if the user input looks like a venue name
        val userVenueName = extractUserVenueName(query)
        Log.d("EventLocationLookup", "Extracted user venue name: '$userVenueName'")
        
        if (userVenueName != null) {
            // User entered what looks like a venue name - try to enhance it
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val addresses = geocoder.getFromLocationName(query, 5) ?: emptyList()
                    var bestVenueName = userVenueName
                    var bestAddress: android.location.Address? = null
                    
                    // Look for venue names that match or enhance the user's input
                    for (address in addresses) {
                        val candidates = extractVenueNameCandidates(address)
                        Log.d("EventLocationLookup", "Address candidates: $candidates")
                        
                        for (candidate in candidates) {
                            if (isEnhancementOfUserInput(userVenueName, candidate)) {
                                Log.d("EventLocationLookup", "Found enhancement: '$candidate' for user input '$userVenueName'")
                                bestVenueName = properlyCapitalizeName(candidate)
                                bestAddress = address
                                break
                            }
                        }
                        if (bestAddress != null) break
                    }
                    
                    // If no enhancement found, use the first address for location info
                    if (bestAddress == null && addresses.isNotEmpty()) {
                        bestAddress = addresses.first()
                        bestVenueName = properlyCapitalizeName(userVenueName)
                    }
                    
                    val fullAddress = bestAddress?.let { buildAddressString(it) }
                    EventLocationDisplay(
                        name = bestVenueName,
                        address = fullAddress?.takeIf { it.isNotEmpty() }
                    )
                }.getOrNull()
            }
            
            if (result != null) {
                Log.d("EventLocationLookup", "Using enhanced venue result: name='${result.name}', address='${result.address}'")
                value = result
            } else {
                // Fallback to just the capitalized user input
                val result = EventLocationDisplay(
                    name = properlyCapitalizeName(userVenueName), 
                    address = null
                )
                Log.d("EventLocationLookup", "Using capitalized user input: name='${result.name}'")
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
 * Extracts venue name candidates from a geocoded address
 */
private fun extractVenueNameCandidates(address: android.location.Address): List<String> {
    val candidates = mutableListOf<String>()
    
    // Check various sources for venue names
    address.extras?.let { extras ->
        listOf("name", "establishment", "point_of_interest", "business_name", "place_name", "feature_name")
            .forEach { key ->
                extras.getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
            }
    }
    
    address.featureName?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
    address.premises?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
    
    // Also check the full address line for venue names at the beginning
    address.getAddressLine(0)?.let { fullLine ->
        val parts = fullLine.split(',').map { it.trim() }
        if (parts.isNotEmpty()) {
            val firstPart = parts[0]
            if (looksLikeVenueName(firstPart) && !firstPart.matches(Regex("^\\d+.*"))) {
                candidates.add(firstPart)
            }
        }
    }
    
    return candidates.distinct().filter { it.length > 2 }
}

/**
 * Checks if a candidate venue name is an enhancement of the user's input
 */
private fun isEnhancementOfUserInput(userInput: String, candidate: String): Boolean {
    val userLower = userInput.lowercase().trim()
    val candidateLower = candidate.lowercase().trim()
    
    // Exact match (case-insensitive)
    if (userLower == candidateLower) return true
    
    // Candidate contains the user input (enhancement)
    if (candidateLower.contains(userLower)) {
        // Make sure it's not just a substring in the middle of a word
        val words = candidateLower.split("\\s+".toRegex())
        val userWords = userLower.split("\\s+".toRegex())
        
        // Check if user words match the beginning of candidate words
        if (userWords.size <= words.size) {
            var matches = true
            for (i in userWords.indices) {
                if (!words[i].startsWith(userWords[i])) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        
        // Or check if the candidate starts with the user input
        return candidateLower.startsWith(userLower)
    }
    
    return false
}

/**
 * Properly capitalizes each word in a venue name
 */
private fun properlyCapitalizeName(name: String): String {
    return name.split("\\s+".toRegex())
        .joinToString(" ") { word ->
            if (word.isEmpty()) {
                word
            } else {
                // Handle words with special characters
                word.lowercase().replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
        }
}

/**
 * Extracts a venue name from user input if it looks like one
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
