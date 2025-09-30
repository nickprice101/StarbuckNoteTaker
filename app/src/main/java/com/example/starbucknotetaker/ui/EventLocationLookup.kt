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
        
        // IRON-CLAD RULE: If user input looks like a venue name, preserve it absolutely
        val userVenueName = extractAndNormalizeVenueName(query)
        Log.d("EventLocationLookup", "Extracted user venue name: '$userVenueName'")
        
        if (userVenueName != null) {
            // User entered a venue name - this is sacred, we must preserve it
            val enhancedResult = withContext(Dispatchers.IO) {
                runCatching {
                    enhanceVenueNameWithGeocoding(geocoder, query, userVenueName)
                }.getOrNull()
            }
            
            if (enhancedResult != null) {
                Log.d("EventLocationLookup", "Using enhanced venue result: name='${enhancedResult.name}', address='${enhancedResult.address}'")
                value = enhancedResult
            } else {
                // Geocoding failed, but we still have the user's venue name
                val result = EventLocationDisplay(name = userVenueName, address = null)
                Log.d("EventLocationLookup", "Using user venue name only: name='${result.name}'")
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
 * IRON-CLAD venue name extraction and normalization
 * This function determines if user input is a venue name and normalizes it
 */
private fun extractAndNormalizeVenueName(query: String): String? {
    val trimmed = query.trim()
    
    // Handle multi-part input (separated by commas or newlines)
    val parts = trimmed.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    
    if (parts.isEmpty()) return null
    
    val firstPart = parts[0]
    
    // Check if the first part looks like a venue name
    if (looksLikeVenueName(firstPart)) {
        return capitalizeEachWord(firstPart)
    }
    
    // If it's a single word/phrase without separators, and it looks like a venue, use it
    if (parts.size == 1 && looksLikeVenueName(trimmed)) {
        return capitalizeEachWord(trimmed)
    }
    
    return null
}

/**
 * Capitalizes each word in a venue name properly
 */
private fun capitalizeEachWord(text: String): String {
    return text.split(' ')
        .joinToString(" ") { word ->
            if (word.isEmpty()) {
                word
            } else {
                word.lowercase().replaceFirstChar { it.titlecase() }
            }
        }
}

/**
 * IRON-CLAD venue name determination
 * This function determines if a string looks like a venue name
 */
private fun looksLikeVenueName(text: String): Boolean {
    val trimmed = text.trim()
    
    // Must have reasonable length
    if (trimmed.length < 2 || trimmed.length > 100) return false
    
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

/**
 * IRON-CLAD venue name enhancement
 * This function tries to enhance/complete venue names using geocoding results
 * but NEVER replaces them with something completely different
 */
private suspend fun enhanceVenueNameWithGeocoding(
    geocoder: Geocoder, 
    originalQuery: String, 
    userVenueName: String
): EventLocationDisplay? {
    return withContext(Dispatchers.IO) {
        try {
            Log.d("EventLocationLookup", "Enhancing venue name '$userVenueName' with geocoding")
            
            // Get geocoding results
            val addresses = geocoder.getFromLocationName(originalQuery, 10) ?: return@withContext null
            
            var bestVenueName = userVenueName
            var bestAddress: String? = null
            
            // Look through results for venue name enhancements
            for ((index, address) in addresses.withIndex()) {
                val addressLine = address.getAddressLine(0) ?: ""
                val fullAddress = buildAddressString(address)
                
                Log.d("EventLocationLookup", "Checking address $index: $addressLine")
                
                // Look for venue names in the address result
                val foundVenueName = extractVenueNameFromAddressLine(addressLine, userVenueName)
                if (foundVenueName != null) {
                    Log.d("EventLocationLookup", "Found enhanced venue name: '$foundVenueName'")
                    bestVenueName = foundVenueName
                    bestAddress = fullAddress
                    break
                }
                
                // If no enhanced venue name found but we have a good address, use it
                if (bestAddress == null && fullAddress.isNotEmpty()) {
                    bestAddress = fullAddress
                }
            }
            
            return@withContext EventLocationDisplay(
                name = bestVenueName,
                address = bestAddress
            )
            
        } catch (e: Exception) {
            Log.e("EventLocationLookup", "Failed to enhance venue name", e)
            return@withContext EventLocationDisplay(name = userVenueName, address = null)
        }
    }
}

/**
 * IRON-CLAD venue name extraction from address lines
 * This function looks for enhanced venue names in geocoding results
 * but only returns names that are clearly related to the user's input
 */
private fun extractVenueNameFromAddressLine(addressLine: String, userVenueName: String): String? {
    // Split the address line by commas to get potential components
    val components = addressLine.split(',').map { it.trim() }
    
    val userLower = userVenueName.lowercase()
    
    for (component in components) {
        val componentLower = component.lowercase()
        
        // Skip obvious address components
        if (looksLikeStreetAddress(component)) continue
        if (looksLikeCity(component)) continue
        if (looksLikePostalCode(component)) continue
        
        // Check if this component contains or extends the user's venue name
        when {
            // Exact match (different case)
            componentLower == userLower -> {
                return capitalizeEachWord(component)
            }
            
            // Component contains the user's venue name (completion case)
            componentLower.contains(userLower) && component.length <= userVenueName.length * 2 -> {
                // Make sure it's not just a substring in a larger address
                if (isVenueNameExtension(component, userVenueName)) {
                    return capitalizeEachWord(component)
                }
            }
            
            // User's venue name contains the component (user typed more than needed)
            userLower.contains(componentLower) && componentLower.length >= 3 -> {
                if (isVenueNameExtension(userVenueName, component)) {
                    return capitalizeEachWord(userVenueName) // Keep user's more complete version
                }
            }
        }
    }
    
    return null
}

/**
 * Checks if one string is a reasonable extension/completion of another for venue names
 */
private fun isVenueNameExtension(longer: String, shorter: String): Boolean {
    val longerLower = longer.lowercase().trim()
    val shorterLower = shorter.lowercase().trim()
    
    // The longer string should start with the shorter one (word boundary)
    if (!longerLower.startsWith(shorterLower)) return false
    
    // The extension should be reasonable (not too long)
    if (longer.length > shorter.length * 3) return false
    
    // Should not contain obvious address components after the venue name
    val extension = longerLower.substring(shorterLower.length).trim()
    if (extension.matches(Regex(".*\\b(street|st|avenue|ave|road|rd|\\d+)\\b.*"))) {
        return false
    }
    
    return true
}

private fun looksLikeStreetAddress(text: String): Boolean {
    return text.matches(Regex("^\\d+.*")) || 
           text.contains(Regex("\\b(street|st|avenue|ave|road|rd|lane|ln|drive|dr|boulevard|blvd|straat|gracht)\\b", RegexOption.IGNORE_CASE))
}

private fun looksLikeCity(text: String): Boolean {
    // This is a simple heuristic - could be improved
    return text.length > 2 && text.matches(Regex("^[A-Za-z\\s-]+$")) && 
           !text.contains(Regex("\\b(the|and|of|van|de|le|la)\\b", RegexOption.IGNORE_CASE))
}

private fun looksLikePostalCode(text: String): Boolean {
    return text.matches(Regex("\\d{4,5}\\s*[A-Z]{0,2}"))
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
