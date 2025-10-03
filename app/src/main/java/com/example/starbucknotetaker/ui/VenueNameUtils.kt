package com.example.starbucknotetaker.ui

internal fun extractAndFormatVenueName(query: String): String? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null

    val parts = trimmed.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null

    val firstPart = parts.first()
    if (isVenueName(firstPart)) {
        return formatVenueName(firstPart)
    }

    if (parts.size == 1 && isVenueName(trimmed)) {
        return formatVenueName(trimmed)
    }

    return null
}

internal fun isVenueName(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.length < 2) return false
    if (trimmed.matches(Regex("^\\d+.*"))) return false
    if (trimmed.matches(Regex("^\\d+$"))) return false

    val addressKeywords = listOf(
        "street", "st", "avenue", "ave", "road", "rd", "lane", "ln",
        "drive", "dr", "boulevard", "blvd", "straat", "gracht",
        "plein", "singel", "kade", "way", "place", "pl", "court", "ct"
    )

    val hasAddressKeyword = addressKeywords.any { keyword ->
        trimmed.contains("\\b$keyword\\b".toRegex(RegexOption.IGNORE_CASE))
    }

    if (hasAddressKeyword) return false

    if (trimmed.matches(Regex("\\d{4,5}\\s*[A-Z]{0,2}"))) return false

    return true
}

internal fun formatVenueName(venueName: String): String {
    return venueName.split(" ").joinToString(" ") { word ->
        if (word.isEmpty()) {
            word
        } else {
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }
}
