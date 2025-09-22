package com.example.starbucknotetaker.ui

import android.location.Address

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

    val addressLine = getAddressLine(0)?.trim()?.takeIf { it.isNotEmpty() }
    if (addressParts.isEmpty()) {
        addressLine
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { addAddressPart(it) }
    }

    val fallbackDisplay = addressLine?.let(::fallbackEventLocationDisplay)
    val normalizedFallbackName = fallbackDisplay?.name?.takeIf { it.isNotBlank() }
    val normalizedFallbackAddress = fallbackDisplay?.address?.takeUnless { it.isNullOrBlank() }

    val primarySource: PrimarySource
    val primary: String = when {
        !poiName.isNullOrEmpty() -> {
            primarySource = PrimarySource.Poi
            poiName
        }
        addressParts.isNotEmpty() -> {
            primarySource = PrimarySource.AddressPart
            addressParts.first()
        }
        normalizedFallbackName != null -> {
            primarySource = PrimarySource.NormalizedFallback
            normalizedFallbackName
        }
        addressLine != null -> {
            primarySource = PrimarySource.AddressLine
            addressLine
        }
        else -> {
            return null
        }
    }

    val combinedAddressParts = addressParts
        .joinToString(separator = ", ")
        .takeIf { it.isNotEmpty() }
    val remainingAddressParts = addressParts
        .drop(1)
        .joinToString(separator = ", ")
        .takeIf { it.isNotEmpty() }

    val secondaryCandidates = mutableListOf<String>()
    fun MutableList<String>.addCandidate(value: String?) {
        val candidate = value?.trim().orEmpty()
        if (candidate.isNotEmpty() &&
            !candidate.equals(primary, ignoreCase = true) &&
            none { it.equals(candidate, ignoreCase = true) }
        ) {
            add(candidate)
        }
    }

    when (primarySource) {
        PrimarySource.Poi -> {
            secondaryCandidates.addCandidate(combinedAddressParts)
            secondaryCandidates.addCandidate(normalizedFallbackAddress)
            secondaryCandidates.addCandidate(addressLine)
        }
        PrimarySource.AddressPart -> {
            secondaryCandidates.addCandidate(remainingAddressParts)
            if (remainingAddressParts.isNullOrEmpty()) {
                secondaryCandidates.addCandidate(combinedAddressParts)
            }
            secondaryCandidates.addCandidate(normalizedFallbackAddress)
            secondaryCandidates.addCandidate(addressLine)
        }
        PrimarySource.NormalizedFallback -> {
            secondaryCandidates.addCandidate(addressLine)
            secondaryCandidates.addCandidate(normalizedFallbackAddress)
            secondaryCandidates.addCandidate(combinedAddressParts)
        }
        PrimarySource.AddressLine -> {
            secondaryCandidates.addCandidate(combinedAddressParts)
            secondaryCandidates.addCandidate(normalizedFallbackAddress)
            secondaryCandidates.addCandidate(remainingAddressParts)
        }
    }

    val secondary = secondaryCandidates.firstOrNull()

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
