package com.example.starbucknotetaker.ui

import android.location.Address
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class EventLocationDisplay(
    val name: String,
    val address: String?,
)

private enum class PrimarySource {
    Poi,
    AddressPart,
    NormalizedFallback,
    AddressLine,
}

internal fun fallbackEventLocationDisplay(raw: String): EventLocationDisplay {
    val normalized = raw.trim()
    if (normalized.isEmpty()) {
        return EventLocationDisplay(name = raw.trim(), address = null)
    }
    
    // Split by newlines first, then by commas
    val lines = normalized.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    val tokens = lines.flatMap { line -> 
        line.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    
    if (tokens.isEmpty()) {
        return EventLocationDisplay(name = normalized, address = null)
    }
    
    // If we have multiple lines, treat the first line as potential venue name
    // and subsequent lines as address components
    if (lines.size > 1) {
        val potentialVenueName = lines.first()
        val addressParts = lines.drop(1)
        
        // Check if first line looks like a venue name (not a street address)
        if (isLikelyVenueName(potentialVenueName)) {
            val address = addressParts.joinToString(", ").takeIf { it.isNotEmpty() }
            return EventLocationDisplay(name = potentialVenueName, address = address)
        }
    }
    
    // For single line entries, try to identify venue name vs street address
    if (tokens.size >= 2) {
        val firstToken = tokens.first()
        
        // If first token looks like a venue name, use it as primary
        if (isLikelyVenueName(firstToken)) {
            val remainingTokens = tokens.drop(1)
            val address = remainingTokens.joinToString(", ").takeIf { it.isNotEmpty() }
            return EventLocationDisplay(name = firstToken, address = address)
        }
    }
    
    // Fallback to original behavior
    val primary = tokens.firstOrNull() ?: normalized
    val secondary = tokens.drop(1)
        .joinToString(separator = ", ")
        .takeIf { it.isNotEmpty() }
    return EventLocationDisplay(name = primary, address = secondary)
}

/**
 * Enhanced venue lookup that tries to find business/venue names at a given address
 */
internal suspend fun lookupVenueAtAddress(geocoder: Geocoder, address: String): EventLocationDisplay? {
    return withContext(Dispatchers.IO) {
        try {
            Log.d("AddressUtils", "Looking up venue for address: $address")
            
            // First try direct geocoding of the address
            val addresses = geocoder.getFromLocationName(address, 10) ?: return@withContext null
            Log.d("AddressUtils", "Found ${addresses.size} geocoded results")
            
            // Look through all results for venue names
            for ((index, geocodedAddress) in addresses.withIndex()) {
                Log.d("AddressUtils", "Checking result $index: ${geocodedAddress.getAddressLine(0)}")
                
                // Log all available data for debugging
                logAddressDetails(geocodedAddress, index)
                
                val venueName = extractValidVenueName(geocodedAddress)
                if (venueName != null) {
                    Log.d("AddressUtils", "Found venue name: $venueName")
                    val fullAddress = buildFullAddress(geocodedAddress)
                    return@withContext EventLocationDisplay(
                        name = venueName,
                        address = fullAddress
                    )
                }
            }
            
            // If no venue found in direct lookup, try coordinate-based lookup with the first result
            val firstAddress = addresses.firstOrNull()
            if (firstAddress?.hasLatitude() == true && firstAddress.hasLongitude()) {
                Log.d("AddressUtils", "Trying coordinate lookup at ${firstAddress.latitude}, ${firstAddress.longitude}")
                
                // Search for businesses/POIs near this coordinate with a larger radius
                val nearbyAddresses = geocoder.getFromLocation(
                    firstAddress.latitude, 
                    firstAddress.longitude, 
                    20
                ) ?: emptyList()
                
                Log.d("AddressUtils", "Found ${nearbyAddresses.size} nearby results")
                
                for ((index, nearbyAddress) in nearbyAddresses.withIndex()) {
                    Log.d("AddressUtils", "Checking nearby result $index: ${nearbyAddress.getAddressLine(0)}")
                    logAddressDetails(nearbyAddress, index)
                    
                    val venueName = extractValidVenueName(nearbyAddress)
                    if (venueName != null) {
                        Log.d("AddressUtils", "Found nearby venue name: $venueName")
                        // Use the original address for the address part, not the nearby one
                        val fullAddress = buildFullAddress(firstAddress)
                        return@withContext EventLocationDisplay(
                            name = venueName,
                            address = fullAddress
                        )
                    }
                }
            }
            
            Log.d("AddressUtils", "No venue name found for address: $address")
            null
        } catch (e: Exception) {
            Log.e("AddressUtils", "Failed to lookup venue at address: $address", e)
            null
        }
    }
}

private fun logAddressDetails(address: Address, index: Int) {
    Log.d("AddressUtils", "Address $index details:")
    Log.d("AddressUtils", "  Address Line: ${address.getAddressLine(0)}")
    Log.d("AddressUtils", "  Feature Name: ${address.featureName}")
    Log.d("AddressUtils", "  Premises: ${address.premises}")
    Log.d("AddressUtils", "  Thoroughfare: ${address.thoroughfare}")
    Log.d("AddressUtils", "  Sub-thoroughfare: ${address.subThoroughfare}")
    Log.d("AddressUtils", "  Locality: ${address.locality}")
    
    // Log extras bundle
    val extras = address.extras
    if (extras != null) {
        Log.d("AddressUtils", "  Extras:")
        for (key in extras.keySet()) {
            Log.d("AddressUtils", "    $key: ${extras.get(key)}")
        }
    }
}

private fun buildFullAddress(address: Address): String {
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

private fun extractValidVenueName(address: Address): String? {
    // Try to get venue name from various sources
    val candidates = mutableListOf<String>()
    
    // Check extras bundle for various venue-related keys
    address.extras?.let { extras ->
        listOf(
            "name",
            "establishment", 
            "point_of_interest",
            "business_name",
            "place_name",
            "feature_name"
        ).forEach { key ->
            extras.getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
        }
    }
    
    // Check standard Address fields
    address.featureName?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
    address.premises?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
    
    Log.d("AddressUtils", "Venue name candidates: $candidates")
    
    // Filter candidates to find valid venue names
    return candidates.firstOrNull { candidate -> isValidVenueName(candidate, address) }
}

private fun isValidVenueName(candidate: String, address: Address): Boolean {
    val trimmed = candidate.trim()
    
    // Must be at least 2 characters
    if (trimmed.length < 2) {
        Log.d("AddressUtils", "Rejecting '$candidate': too short")
        return false
    }
    
    // Must not be purely numeric (street numbers)
    if (trimmed.matches(Regex("^\\d+[A-Za-z]*$"))) {
        Log.d("AddressUtils", "Rejecting '$candidate': looks like street number")
        return false
    }
    
    // Must not match street name or number
    val thoroughfare = address.thoroughfare?.trim()
    val subThoroughfare = address.subThoroughfare?.trim()
    
    if (trimmed.equals(thoroughfare, ignoreCase = true) || 
        trimmed.equals(subThoroughfare, ignoreCase = true)) {
        Log.d("AddressUtils", "Rejecting '$candidate': matches street components")
        return false
    }
    
    // Must not contain obvious address patterns
    val addressPatterns = listOf(
        Regex("^\\d+\\s+\\w+"),  // "123 Main St"
        Regex("\\b(Street|St|Avenue|Ave|Road|Rd|Lane|Ln|Drive|Dr|Boulevard|Blvd|Straat|Gracht|Plein|Singel|Kade)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b\\d{4,5}\\s*[A-Z]{0,2}\\b")  // Postal codes
    )
    
    if (addressPatterns.any { it.containsMatchIn(trimmed) }) {
        Log.d("AddressUtils", "Rejecting '$candidate': contains address patterns")
        return false
    }
    
    // Should not be a city, region, or country name
    val locationNames = listOfNotNull(
        address.locality,
        address.subAdminArea,
        address.adminArea,
        address.countryName
    ).map { it.trim() }
    
    if (locationNames.any { it.equals(trimmed, ignoreCase = true) }) {
        Log.d("AddressUtils", "Rejecting '$candidate': matches geographic location")
        return false
    }
    
    Log.d("AddressUtils", "Accepting '$candidate' as valid venue name")
    return true
}

/**
 * Attempts to determine if a string looks like a venue/place name rather than a street address
 */
private fun isLikelyVenueName(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    
    // Common patterns that suggest this is NOT a venue name (i.e., it's likely an address)
    val addressPatterns = listOf(
        // Street number patterns
        Regex("^\\d+\\s+\\w+"),  // "123 Main St", "234A Some Street"
        // Postal code patterns  
        Regex("\\b\\d{4,5}\\s*[A-Z]{0,2}\\b"),  // "1017 PH", "90210"
        // Common address words
        Regex("\\b(Street|St|Avenue|Ave|Road|Rd|Lane|Ln|Drive|Dr|Boulevard|Blvd|Way|Place|Pl|Court|Ct|Straat|Gracht|Plein|Singel|Kade)\\b", RegexOption.IGNORE_CASE),
        // Geographic indicators that usually appear in addresses
        Regex("\\b(North|South|East|West|N|S|E|W|Noord|Zuid|Oost|West)\\s+\\w+", RegexOption.IGNORE_CASE),
    )
    
    // If any address pattern matches, it's likely an address, not a venue name
    if (addressPatterns.any { it.containsMatchIn(trimmed) }) {
        return false
    }
    
    // Positive indicators that this might be a venue name
    val venueIndicators = listOf(
        // Single word or short phrases (venue names are often concise)
        trimmed.split("\\s+".toRegex()).size <= 3,
        // Proper capitalization (venue names are usually title case)
        trimmed.split("\\s+".toRegex()).all { word -> 
            word.isNotEmpty() && (word[0].isUpperCase() || !word[0].isLetter())
        }
    )
    
    // If it has positive venue indicators and no negative address patterns, treat as venue
    return venueIndicators.any { it }
}

internal fun Address.toSuggestion(): String? {
    val components = linkedSetOf<String>()

    fun addIfUseful(value: String?) {
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { components.add(it) }
    }

    // Prioritize POI name if available
    val poiName = extractValidVenueName(this)
    if (!poiName.isNullOrBlank()) {
        addIfUseful(poiName)
    }
    
    addIfUseful(thoroughfare?.let { street ->
        subThoroughfare?.let { number -> "$number $street" } ?: street
    })
    addIfUseful(locality)
    addIfUseful(subAdminArea)
    addIfUseful(adminArea)
    addIfUseful(countryName)

    val summary = components.joinToString(separator = ", ")
    if (summary.isNotEmpty()) {
        return summary
    }

    val addressLine = getAddressLine(0)?.trim()
    return addressLine.takeUnless { it.isNullOrEmpty() }
}

internal fun Address.toEventLocationDisplay(): EventLocationDisplay? {
    return toEventLocationDisplayWithVenueLookup()
}

internal fun Address.toEventLocationDisplayWithVenueLookup(): EventLocationDisplay? {
    val venueName = extractValidVenueName(this)
    
    // Only return venue display if we found a proper venue name
    if (!venueName.isNullOrBlank()) {
        val fullAddress = buildFullAddress(this)
        return EventLocationDisplay(
            name = venueName,
            address = fullAddress.takeIf { it.isNotEmpty() && !it.equals(venueName, ignoreCase = true) }
        )
    }
    
    return null
}

private fun isStreetAddress(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.matches(Regex("^\\d+\\s*[A-Za-z]*\\s+.+")) || // "123A Main Street"
           trimmed.contains(Regex("\\b(Street|St|Avenue|Ave|Road|Rd|Lane|Ln|Drive|Dr|Boulevard|Blvd|Straat|Gracht|Plein|Singel|Kade)\\b", RegexOption.IGNORE_CASE))
}

internal fun EventLocationDisplay.mergeWithFallback(fallback: EventLocationDisplay): EventLocationDisplay {
    val mergedName = name.ifBlank { fallback.name }
    val mergedAddress = (address ?: fallback.address)
        ?.takeUnless { it.equals(mergedName, ignoreCase = true) }
    return EventLocationDisplay(
        name = mergedName.ifBlank { fallback.name },
        address = mergedAddress
    )
}

private fun Address.extractPoiName(): String? {
    return extractValidVenueName(this)
}

private fun MutableList<String>.addCandidate(values: List<String>) {
    val joined = values.joinToString(", ")
    if (joined.isNotEmpty() && !contains(joined)) {
        add(joined)
    }
    values.forEach { value ->
        if (value.isNotEmpty() && !contains(value)) {
            add(value)
        }
    }
}
