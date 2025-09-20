package com.example.starbucknotetaker.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

internal fun formatZoneCode(
    zoneId: ZoneId,
    locale: Locale = Locale.getDefault(),
    instant: Instant = Instant.now(),
): String {
    val offset = zoneId.rules.getOffset(instant)
    val totalSeconds = offset.totalSeconds
    val hours = totalSeconds / 3600
    val minutes = abs(totalSeconds % 3600) / 60

    val rawShortName = zoneId.getDisplayName(TextStyle.SHORT, locale)
    val sanitizedShortName = rawShortName.takeIf { shortName ->
        shortName.isNotBlank() &&
            shortName.any { it.isLetter() } &&
            shortName.none { it.isDigit() } &&
            !shortName.contains('+') &&
            !shortName.contains('-')
    } ?: "GMT"

    val sign = if (totalSeconds >= 0) "+" else "-"
    val hourComponent = String.format(Locale.US, "%d", abs(hours))
    val minuteComponent = if (minutes > 0) {
        String.format(Locale.US, ":%02d", minutes)
    } else {
        ""
    }

    return buildString {
        append(sanitizedShortName)
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
