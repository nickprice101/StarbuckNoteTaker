package com.example.starbucknotetaker.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

internal fun formatZoneCode(
    zoneId: ZoneId,
    locale: Locale = Locale.getDefault(),
    instant: Instant = Instant.now(),
): String {
    val zonedDateTime = instant.atZone(zoneId)
    val formatter = DateTimeFormatter.ofPattern("zzz", locale)
    val shortName = runCatching { formatter.format(zonedDateTime) }
        .getOrNull()
        .orEmpty()
        .trim()

    val normalized = shortName.takeIf { it.isNotEmpty() }
    val looksLikeRawOffset = normalized?.let {
        (it.startsWith("GMT", ignoreCase = true) || it.startsWith("UTC", ignoreCase = true)) && it.length > 3
    } == true

    if (normalized != null && !looksLikeRawOffset) {
        return normalized
    }

    val offset = zoneId.rules.getOffset(instant)
    val totalSeconds = offset.totalSeconds
    val hours = totalSeconds / 3600
    val minutes = abs(totalSeconds % 3600) / 60

    val base = if (totalSeconds == 0) {
        "GMT"
    } else {
        "UTC"
    }

    val sign = if (totalSeconds >= 0) "+" else "-"
    val hourComponent = String.format(Locale.US, "%d", abs(hours))
    val minuteComponent = if (minutes > 0) {
        String.format(Locale.US, ":%02d", minutes)
    } else {
        ""
    }

    return buildString {
        append(base)
        append(sign)
        append(hourComponent)
        append(minuteComponent)
    }
}

internal fun zoneSearchStrings(zoneId: ZoneId, locale: Locale): List<String> {
    val id = zoneId.id
    val fullName = zoneId.getDisplayName(TextStyle.FULL, locale)
    val shortName = zoneId.getDisplayName(TextStyle.SHORT, locale)
    val city = id.substringAfterLast('/')
        .replace('_', ' ')
    val region = id.substringBefore('/', missingDelimiterValue = "")
        .replace('_', ' ')
    val code = formatZoneCode(zoneId, locale)
    return listOf(id, fullName, shortName, city, region, code)
}

internal fun zoneIdDisplayName(zoneId: ZoneId): String {
    return zoneId.id.replace('_', ' ')
}
