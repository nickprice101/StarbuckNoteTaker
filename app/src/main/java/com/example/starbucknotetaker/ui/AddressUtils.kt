package com.example.starbucknotetaker.ui

import android.location.Address

internal data class EventLocationDisplay(
    val name: String,
    val address: String?,
)

internal fun fallbackEventLocationDisplay(raw: String): EventLocationDisplay {
    val normalized = raw.trim()
    if (normalized.isEmpty()) {
        return EventLocationDisplay(name = raw.trim(), address = null)
    }
    val tokens = normalized
        .split('\n')
        .flatMap { segment -> segment.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val primary = tokens.firstOrNull() ?: normalized
    val secondary = tokens.drop(1)
        .joinToString(separator = ", ")
        .takeIf { it.isNotEmpty() }
    return EventLocationDisplay(name = primary, address = secondary)
}

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

internal fun Address.toEventLocationDisplay(): EventLocationDisplay? {
    val poiName = extractPoiName()
    val street = listOfNotNull(thoroughfare?.trim(), subThoroughfare?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(separator = " ")
        .takeIf { it.isNotEmpty() }
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

    val addressParts = mutableListOf<String>()
    fun addAddressPart(value: String?) {
        val cleaned = value?.trim().orEmpty()
        if (cleaned.isNotEmpty() && cleaned !in addressParts) {
            addressParts.add(cleaned)
        }
    }

    addAddressPart(street)
    addAddressPart(cityLine)
    addAddressPart(subLocality?.takeUnless { it.equals(locality, ignoreCase = true) })
    addAddressPart(subAdminArea?.takeUnless { it.equals(locality, ignoreCase = true) })
    addAddressPart(adminArea)
    addAddressPart(countryName)

    if (addressParts.isEmpty()) {
        val addressLine = getAddressLine(0)?.trim()
        addressLine?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { addAddressPart(it) }
    }

    val primary = when {
        !poiName.isNullOrEmpty() -> poiName
        addressParts.isNotEmpty() -> addressParts.first()
        else -> getAddressLine(0)?.trim()?.takeIf { it.isNotEmpty() }
    } ?: return null

    val secondaryParts = when {
        !poiName.isNullOrEmpty() -> addressParts
        addressParts.size > 1 -> addressParts.drop(1)
        else -> emptyList()
    }

    val secondary = secondaryParts
        .filter { !it.equals(primary, ignoreCase = true) }
        .joinToString(separator = ", ")
        .takeIf { it.isNotEmpty() }
        ?: getAddressLine(0)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(primary, ignoreCase = true) }

    return EventLocationDisplay(
        name = primary,
        address = secondary?.takeUnless { it.equals(primary, ignoreCase = true) }
    )
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
