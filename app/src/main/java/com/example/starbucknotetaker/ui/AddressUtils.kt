package com.example.starbucknotetaker.ui

import android.location.Address
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

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
 * Enhanced venue lookup that tries to find business/venue names at a given address,
 * with fuzzy matching against the original search query
 */
internal suspend fun lookupVenueAtAddress(geocoder: Geocoder, originalQuery: String): EventLocationDisplay? {
    return withContext(Dispatchers.IO) {
        try {
            Log.d("AddressUtils", "Looking up venue for query: $originalQuery")
            
            // Extract potential venue name from the original query
            val potentialVenueName = extractPotentialVenueFromQuery(originalQuery)
            Log.d("AddressUtils", "Potential venue name from query: $potentialVenueName")
            
            // First try direct geocoding of the original query
            val addresses = geocoder.getFromLocationName(originalQuery, 15) ?: return@withContext null
            Log.d("AddressUtils", "Found ${addresses.size} geocoded results")
            
            // Look through all results for venue names, prioritizing matches to the query
            val candidatesWithScores = mutableListOf<Pair<EventLocationDisplay, Double>>()
            
            for ((index, geocodedAddress) in addresses.withIndex()) {
                Log.d("AddressUtils", "Checking result $index: ${geocodedAddress.getAddressLine(0)}")
                logAddressDetails(geocodedAddress, index)
                
                val venueName = extractValidVenueName(geocodedAddress)
                if (venueName != null) {
                    val fullAddress = buildFullAddress(geocodedAddress)
                    val candidate = EventLocationDisplay(name = venueName, address = fullAddress)
                    
                    // Calculate similarity score
                    val score = calculateVenueMatchScore(venueName, originalQuery, potentialVenueName)
                    candidatesWithScores.add(candidate to score)
                    
                    Log.d("AddressUtils", "Found venue '$venueName' with score: $score")
                }
            }
            
            // If we found candidates, return the best match
            if (candidatesWithScores.isNotEmpty()) {
                val bestMatch = candidatesWithScores.maxByOrNull { it.second }?.first
                if (bestMatch != null) {
                    Log.d("AddressUtils", "Best venue match: ${bestMatch.name}")
                    return@withContext bestMatch
                }
            }
            
            // If no good direct matches, try coordinate-based lookup with the first result
            val firstAddress = addresses.firstOrNull()
            if (firstAddress?.hasLatitude() == true && firstAddress.hasLongitude()) {
                Log.d("AddressUtils", "Trying coordinate lookup at ${firstAddress.latitude}, ${firstAddress.longitude}")
                
                val nearbyAddresses = geocoder.getFromLocation(
                    firstAddress.latitude, 
                    firstAddress.longitude, 
                    20
                ) ?: emptyList()
                
                Log.d("AddressUtils", "Found ${nearbyAddresses.size} nearby results")
                
                val nearbyCandidates = mutableListOf<Pair<EventLocationDisplay, Double>>()
                
                for ((index, nearbyAddress) in nearbyAddresses.withIndex()) {
                    val venueName = extractValidVenueName(nearbyAddress)
                    if (venueName != null) {
                        // Use the original address for the address part
                        val fullAddress = buildFullAddress(firstAddress)
                        val candidate = EventLocationDisplay(name = venueName, address = fullAddress)
                        
                        val score = calculateVenueMatchScore(venueName, originalQuery, potentialVenueName)
                        nearbyCandidates.add(candidate to score)
                        
                        Log.d("AddressUtils", "Found nearby venue '$venueName' with score: $score")
                    }
                }
                
                // Return the best nearby match if it's good enough
                val bestNearbyMatch = nearbyCandidates.maxByOrNull { it.second }
                if (bestNearbyMatch != null && bestNearbyMatch.second > 0.3) { // Minimum threshold
                    Log.d("AddressUtils", "Best nearby venue match: ${bestNearbyMatch.first.name}")
                    return@withContext bestNearbyMatch.first
                }
            }
            
            Log.d("AddressUtils", "No good venue match found for query: $originalQuery")
            null
        } catch (e: Exception) {
            Log.e("AddressUtils", "Failed to lookup venue for query: $originalQuery", e)
            null
        }
    }
}

/**
 * Extracts potential venue name from the original user query
 */
private fun extractPotentialVenueFromQuery(query: String): String? {
    val tokens = query.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    
    // Look for the first token that looks like a venue name
    return tokens.firstOrNull { token ->
        isLikelyVenueName(token) && token.length > 2
    }
}

/**
 * Calculates how well a venue name matches the original search query
 */
private fun calculateVenueMatchScore(
    venueName: String, 
    originalQuery: String, 
    potentialVenueName: String?
): Double {
    val venue = venueName.lowercase().trim()
    val query = originalQuery.lowercase().trim()
    
    // Perfect match
    if (venue == query) return 1.0
    
    // Check if venue name is contained in or contains the query
    when {
        venue.contains(query) -> return 0.9
        query.contains(venue) -> return 0.9
    }
    
    // If we extracted a potential venue name from query, compare against that
    if (potentialVenueName != null) {
        val potential = potentialVenueName.lowercase().trim()
        when {
            venue == potential -> return 0.95
            venue.contains(potential) -> return 0.8
            potential.contains(venue) -> return 0.8
        }
    }
    
    // Calculate Levenshtein distance similarity
    val maxLen = maxOf(venue.length, query.length)
    if (maxLen == 0) return 0.0
    
    val distance = levenshteinDistance(venue, query)
    val similarity = 1.0 - (distance.toDouble() / maxLen)
    
    // Also try comparing with just the first word of the query
    val firstQueryWord = query.split(' ', ',').firstOrNull()?.trim()
    if (firstQueryWord != null && firstQueryWord.length > 2) {
        val firstWordDistance = levenshteinDistance(venue, firstQueryWord)
        val firstWordMaxLen = maxOf(venue.length, firstQueryWord.length)
        val firstWordSimilarity = if (firstWordMaxLen > 0) {
            1.0 - (firstWordDistance.toDouble() / firstWordMaxLen)
        } else 0.0
        
        // Use the better of the two similarities
        return maxOf(similarity, firstWordSimilarity)
    }
    
    return similarity
}

/**
 * Calculates Levenshtein distance between two strings
 */
private fun levenshteinDistance(str1: String, str2: String): Int {
    val len1 = str1.length
    val len2 = str2.length
    val matrix = Array(len1 + 1) { IntArray(len2 + 1) }
    
    for (i in 0..len1) {
        matrix[i][0] = i
    }
    for (j in 0..len2) {
        matrix[0][j] = j
    }
    
    for (i in 1..len1) {
        for (j in 1..len2) {
            val cost = if (str1[i - 1] == str2[j - 1]) 0 else 1
            matrix[i][j] = min(
                min(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1),
                matrix[i - 1][j - 1] + cost
            )
        }
    }
    
    return matrix[len1][len2]
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
