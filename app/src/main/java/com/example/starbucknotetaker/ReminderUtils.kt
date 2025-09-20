package com.example.starbucknotetaker

/**
 * Supported reminder offsets expressed in minutes before the event start time.
 */
val REMINDER_MINUTE_OPTIONS = listOf(0, 5, 10, 15, 30, 60, 120, 240, 1440)

/**
 * Formats a reminder offset into a human readable label.
 */
fun formatReminderOffsetMinutes(minutes: Int): String {
    if (minutes <= 0) {
        return "At start time"
    }
    val days = minutes / 1440
    if (minutes % 1440 == 0 && days > 0) {
        return if (days == 1) "1 day before" else "$days days before"
    }
    val hours = minutes / 60
    if (minutes % 60 == 0 && hours > 0) {
        return if (hours == 1) "1 hour before" else "$hours hours before"
    }
    return if (minutes == 1) "1 minute before" else "$minutes minutes before"
}
