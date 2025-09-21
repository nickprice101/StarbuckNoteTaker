package com.example.starbucknotetaker.ui

import android.location.Address

internal data class EventLocationDisplay(
    val name: String,
    val address: String?,
)

internal fun Address.toSuggestion(): String? {
    val components = linkedSetOf<String>()

    fun addIfUseful(value: String?) {
        val cleaned = value?.trim().orEmpty()
        if (cleaned.isNotEmpty()) {
            components.add(cleaned)
        }
    }

    val poiName = extractPoiName()
    addIfUseful(poiName)

    val street = listOfNotNull(thoroughfare?.trim(), subThoroughfare?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(separator = " ")
        .takeIf { it.isNotEmpty() }
    addIfUseful(street)

    val cityLine = buildList {
        val postal = postalCode?.trim()
        if (!postal.isNullOrEmpty()) {
            add(postal)
        }
        val city = locality?.trim()
        if (!city.isNullOrEmpty()) {
            add(city)
        }
    }.joinToString(separator = " ")
        .takeIf { it.isNotEmpty() }
    addIfUseful(cityLine)

    addIfUseful(subLocality?.takeUnless { it.equals(locality, ignoreCase = true) })
    addIfUseful(subAdminArea?.takeUnless { it.equals(locality, ignoreCase = true) })
    addIfUseful(adminArea)
    addIfUseful(countryName)

    val summary = components.joinToString(separator = ", ")
    if (summary.isNotEmpty()) {
        return summary
    }

    val addressLine = getAddressLine(0)?.trim()
    return addressLine.takeUnless { it.isNullOrEmpty() }
}

internal fun Address.toEventLocationDisplay(fallback: String): EventLocationDisplay? {
    val normalizedFallback = fallback.trim()
    val poiName = extractPoiName()
    val addressLine = getAddressLine(0)?.trim()?.takeIf { it.isNotEmpty() }
    val primary = when {
        !poiName.isNullOrEmpty() -> poiName
        !addressLine.isNullOrEmpty() -> addressLine
        normalizedFallback.isNotEmpty() -> normalizedFallback
        else -> return null
    }
    val secondary = when {
        !poiName.isNullOrEmpty() -> addressLine?.takeUnless { it.equals(primary, ignoreCase = true) }
        primary != normalizedFallback && normalizedFallback.isNotEmpty() -> normalizedFallback
        else -> null
    }
    return EventLocationDisplay(name = primary, address = secondary)
}

private fun Address.extractPoiName(): String? {
    return listOfNotNull(
        extras?.getString("name"),
        extras?.getString("feature_name"),
        featureName,
        premises,
    ).map { it.trim() }
        .firstOrNull { candidate ->
            candidate.isNotEmpty() &&
                !candidate.equals(thoroughfare?.trim(), ignoreCase = true) &&
                !candidate.equals(subThoroughfare?.trim(), ignoreCase = true) &&
                !candidate.isLikelyHouseNumber()
        }
}

private fun String.isLikelyHouseNumber(): Boolean {
    val normalized = trim()
    if (normalized.isEmpty()) return false
    if (!normalized.first().isDigit()) return false
    return normalized.length <= 6 && normalized.all { it.isLetterOrDigit() || it == '-' }
}
