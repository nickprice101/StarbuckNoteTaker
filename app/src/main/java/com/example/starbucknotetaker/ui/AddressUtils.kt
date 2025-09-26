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
            // First try direct geocoding of the address
            val addresses = geocoder.getFromLocationName(address, 5) ?: return@withContext null
            
            for (geocodedAddress in addresses) {
                // Try to extract venue name from this address result
                val venueDisplay = geocodedAddress.toEventLocationDisplayWithVenueLookup()
                if (venueDisplay != null) {
                    return@withContext venueDisplay
                }
            }
            
            // If no venue found in direct lookup, try coordinate-based lookup
            val firstAddress = addresses.firstOrNull()
            if (firstAddress?.hasLatitude() == true && firstAddress.hasLongitude()) {
                val lat = firstAddress.latitude
                val lng = firstAddress.longitude
                
                // Search for businesses/POIs near this coordinate
                val nearbyAddresses = geocoder.getFromLocation(lat, lng, 10) ?: emptyList()
                
                for (nearbyAddress in nearbyAddresses) {
                    val venueDisplay = nearbyAddress.toEventLocationDisplayWithVenueLookup()
                    if (venueDisplay != null) {
                        // Verify this venue is actually at or very close to our target address
                        if (addressesAreAtSameLocation(firstAddress, nearbyAddress)) {
                            return@withContext venueDisplay
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            Log.w("AddressUtils", "Failed to lookup venue at address: $address", e)
            null
        }
    }
}

private fun addressesAreAtSameLocation(address1: Address, address2: Address): Boolean {
    // Check if addresses are at the same location (within ~50 meters)
    if (!address1.hasLatitude() || !address1.hasLongitude() || 
        !address2.hasLatitude() || !address2.hasLongitude()) {
        return false
    }
    
    val distance = FloatArray(1)
    android.location.Location.distanceBetween(
        address1.latitude, address1.longitude,
        address2.latitude, address2.longitude,
        distance
    )
    
    return distance[0] < 50f // Within 50 meters
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
    val poiName = extractPoiName()
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
    // Extract all possible name sources with priority order
    val poiName = extractPoiName()
    val featureName = this.featureName?.trim()?.takeIf { it.isNotEmpty() }
    val establishmentName = extras?.getString("establishment")?.trim()?.takeIf { it.isNotEmpty() }
    val placeName = extras?.getString("place_name")?.trim()?.takeIf { it.isNotEmpty() }
    val venueName = extras?.getString("name")?.trim()?.takeIf { it.isNotEmpty() }
    
    // Build comprehensive address components
    val street = buildStreetAddress()
    val cityLine = buildCityLine()
    val regionLine = buildRegionLine()
    
    val addressParts = mutableListOf<String>()
    fun addAddressPart(value: String?) {
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }
    }

    addAddressPart(street)
    addAddressPart(cityLine)
    addAddressPart(regionLine)
    addAddressPart(countryName?.trim())

    // Determine the best primary name to use
    val primaryName = when {
        // Prioritize venue/establishment names over feature names
        !venueName.isNullOrBlank() && !isStreetAddress(venueName) -> venueName
        !establishmentName.isNullOrBlank() && !isStreetAddress(establishmentName) -> establishmentName
        !placeName.isNullOrBlank() && !isStreetAddress(placeName) -> placeName
        !poiName.isNullOrBlank() && !isStreetAddress(poiName) -> poiName
        !featureName.isNullOrBlank() && !isStreetAddress(featureName) -> featureName
        else -> null
    }

    // Only return venue display if we found a proper venue name
    if (!primaryName.isNullOrBlank()) {
        val fullAddress = addressParts.joinToString(", ")
        return EventLocationDisplay(
            name = primaryName,
            address = fullAddress.takeIf { it.isNotEmpty() && !it.equals(primaryName, ignoreCase = true) }
        )
    }
    
    return null
}

private fun Address.buildStreetAddress(): String? {
    val parts = mutableListOf<String>()
    subThoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    thoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private fun Address.buildCityLine(): String? {
    val parts = mutableListOf<String>()
    postalCode?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    locality?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private fun Address.buildRegionLine(): String? {
    val parts = mutableListOf<String>()
    
    // Add sub-admin area if it's different from locality
    val subAdmin = subAdminArea?.trim()?.takeIf { it.isNotEmpty() }
    val local = locality?.trim()
    if (subAdmin != null && !subAdmin.equals(local, ignoreCase = true)) {
        parts.add(subAdmin)
    }
    
    adminArea?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
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
    // Try multiple sources for POI names, prioritizing establishment names
    val candidates = listOfNotNull(
        extras?.getString("establishment"),
        extras?.getString("point_of_interest"),
        extras?.getString("name"),
        extras?.getString("feature_name"),
        extras?.getString("place_name"),
        extras?.getString("business_name"),
        featureName,
        premises,
    ).map { it.trim() }.filter { it.isNotEmpty() }

    return candidates.firstOrNull { candidate ->
        !candidate.equals(thoroughfare?.trim(), ignoreCase = true) &&
        !candidate.equals(subThoroughfare?.trim(), ignoreCase = true) &&
        !isStreetAddress(candidate) &&
        candidate.length > 1 // Avoid single character names
    }
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
