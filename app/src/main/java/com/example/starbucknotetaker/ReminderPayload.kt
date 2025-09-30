package com.example.starbucknotetaker

import android.content.Intent

data class ReminderPayload(
    val noteId: Long,
    val title: String,
    val summary: String,
    val eventStart: Long,
    val timeZoneId: String,
    val allDay: Boolean,
    val location: String?,
    val isLocked: Boolean,
    val reminderMinutes: Int,
    val fallbackToNotification: Boolean,
    val kind: Kind,
) {
    fun copyForFallback(fallback: Boolean): ReminderPayload {
        return copy(fallbackToNotification = fallback)
    }

    fun fillIntent(intent: Intent) {
        intent.putExtra(EXTRA_NOTE_ID, noteId)
        intent.putExtra(EXTRA_NOTE_TITLE, title)
        intent.putExtra(EXTRA_NOTE_SUMMARY, summary)
        intent.putExtra(EXTRA_EVENT_START, eventStart)
        intent.putExtra(EXTRA_EVENT_TIME_ZONE, timeZoneId)
        intent.putExtra(EXTRA_EVENT_ALL_DAY, allDay)
        intent.putExtra(EXTRA_EVENT_LOCATION, location)
        intent.putExtra(EXTRA_NOTE_LOCKED, isLocked)
        intent.putExtra(EXTRA_REMINDER_MINUTES, reminderMinutes)
        intent.putExtra(EXTRA_FALLBACK_TO_NOTIFICATION, fallbackToNotification)
        intent.putExtra(EXTRA_KIND, kind.name)
    }

    fun requestCode(): Int {
        return (noteId.hashCode() * 31) + kind.ordinal
    }

    companion object {
        internal const val EXTRA_NOTE_ID = "extra_note_id"
        private const val EXTRA_NOTE_TITLE = "extra_note_title"
        private const val EXTRA_NOTE_SUMMARY = "extra_note_summary"
        private const val EXTRA_EVENT_START = "extra_event_start"
        private const val EXTRA_EVENT_TIME_ZONE = "extra_event_time_zone"
        private const val EXTRA_EVENT_ALL_DAY = "extra_event_all_day"
        private const val EXTRA_EVENT_LOCATION = "extra_event_location"
        private const val EXTRA_NOTE_LOCKED = "extra_note_locked"
        private const val EXTRA_REMINDER_MINUTES = "extra_reminder_minutes"
        private const val EXTRA_FALLBACK_TO_NOTIFICATION = "extra_fallback_to_notification"
        private const val EXTRA_KIND = "extra_reminder_kind"

        fun fromIntent(intent: Intent?): ReminderPayload? {
            intent ?: return null
            if (!intent.hasExtra(EXTRA_NOTE_ID)) {
                return null
            }
            val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1)
            if (noteId == -1L) {
                return null
            }
            val kindName = intent.getStringExtra(EXTRA_KIND)
            val kind = kindName?.let { name ->
                runCatching { Kind.valueOf(name) }.getOrDefault(Kind.ALARM)
            } ?: Kind.ALARM
            return ReminderPayload(
                noteId = noteId,
                title = intent.getStringExtra(EXTRA_NOTE_TITLE).orEmpty(),
                summary = intent.getStringExtra(EXTRA_NOTE_SUMMARY).orEmpty(),
                eventStart = intent.getLongExtra(EXTRA_EVENT_START, 0L),
                timeZoneId = intent.getStringExtra(EXTRA_EVENT_TIME_ZONE).orEmpty(),
                allDay = intent.getBooleanExtra(EXTRA_EVENT_ALL_DAY, false),
                location = intent.getStringExtra(EXTRA_EVENT_LOCATION),
                isLocked = intent.getBooleanExtra(EXTRA_NOTE_LOCKED, false),
                reminderMinutes = intent.getIntExtra(EXTRA_REMINDER_MINUTES, 0),
                fallbackToNotification = intent.getBooleanExtra(EXTRA_FALLBACK_TO_NOTIFICATION, false),
                kind = kind,
            )
        }

        fun fromNote(
            note: Note,
            minutesBeforeStart: Int,
            fallbackToNotification: Boolean,
            kind: Kind,
        ): ReminderPayload {
            val event = note.event ?: error("Note must have an event to create a reminder payload")
            return ReminderPayload(
                noteId = note.id,
                title = note.title,
                summary = note.summary,
                eventStart = event.start,
                timeZoneId = event.timeZone,
                allDay = event.allDay,
                location = event.location,
                isLocked = note.isLocked,
                reminderMinutes = minutesBeforeStart,
                fallbackToNotification = fallbackToNotification,
                kind = kind,
            )
        }
    }

    enum class Kind {
        ALARM,
        REMINDER,
    }
}
