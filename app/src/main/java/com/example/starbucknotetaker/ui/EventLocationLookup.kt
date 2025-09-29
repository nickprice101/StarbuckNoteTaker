@Composable
internal fun rememberEventLocationDisplay(location: String?): EventLocationDisplay? {
    val query = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    
    // CRITICAL FIX: Check immediately if this looks like a venue name
    val isLikelyVenueName = query.matches(Regex("^[A-Za-z][A-Za-z\\s'-]*$")) && // Only letters, spaces, apostrophes, hyphens
                           query.length in 2..30 && // Reasonable length
                           !query.contains(Regex("\\b(street|st|avenue|ave|road|rd|lane|ln|drive|dr|boulevard|blvd|straat|gracht|plein|singel|kade|way|place|court)\\b", RegexOption.IGNORE_CASE))
    
    if (isLikelyVenueName) {
        // User entered a venue name - preserve it absolutely
        val geocoderAvailable = remember { Geocoder.isPresent() }
        if (!geocoderAvailable) {
            return EventLocationDisplay(name = query, address = null)
        }
        
        val context = LocalContext.current
        val geocoder = remember(context) { Geocoder(context, Locale.getDefault()) }
        
        val display by produceState(initialValue = EventLocationDisplay(name = query, address = null)) {
            val geocodedAddress = withContext(Dispatchers.IO) {
                runCatching {
                    geocoder.getFromLocationName(query, 1)?.firstOrNull()
                }.getOrNull()
            }
            
            if (geocodedAddress != null) {
                val addressString = buildSimpleAddress(geocodedAddress)
                value = EventLocationDisplay(
                    name = query, // ALWAYS preserve the user's venue name
                    address = addressString.takeIf { it.isNotEmpty() }
                )
            } else {
                value = EventLocationDisplay(name = query, address = null)
            }
        }
        
        return display
    }
    
    // If not a venue name, fall back to existing complex logic
    val fallback = remember(query) { fallbackEventLocationDisplay(query) }
    // ... rest of existing logic
}

private fun buildSimpleAddress(address: android.location.Address): String {
    val parts = mutableListOf<String>()
    
    // Street
    val street = listOfNotNull(address.subThoroughfare, address.thoroughfare)
        .joinToString(" ").takeIf { it.isNotBlank() }
    street?.let { parts.add(it) }
    
    // City with postal code
    val city = listOfNotNull(address.postalCode, address.locality)
        .joinToString(" ").takeIf { it.isNotBlank() }
    city?.let { parts.add(it) }
    
    // Region and country
    address.subAdminArea?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    address.adminArea?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    address.countryName?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    
    return parts.joinToString(", ")
}
