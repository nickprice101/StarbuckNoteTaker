package com.example.starbucknotetaker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class NoteAlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun scheduleIfNeeded(note: Note) {
        val manager = alarmManager
        if (manager == null) {
            Log.w(TAG, "scheduleIfNeeded: AlarmManager unavailable")
            return
        }
        val event = note.event
        if (event == null) {
            cancel(note.id)
            return
        }

        scheduleReminder(
            manager = manager,
            note = note,
            event = event,
            minutesBefore = event.alarmMinutesBeforeStart,
            kind = ReminderPayload.Kind.ALARM,
        )
        scheduleReminder(
            manager = manager,
            note = note,
            event = event,
            minutesBefore = event.notificationMinutesBeforeStart,
            kind = ReminderPayload.Kind.REMINDER,
        )
    }

    fun cancel(noteId: Long) {
        cancel(noteId, ReminderPayload.Kind.ALARM)
        cancel(noteId, ReminderPayload.Kind.REMINDER)
    }

    fun syncNotes(notes: List<Note>) {
        notes.forEach { note -> scheduleIfNeeded(note) }
    }

    private fun scheduleReminder(
        manager: AlarmManager,
        note: Note,
        event: NoteEvent,
        minutesBefore: Int?,
        kind: ReminderPayload.Kind,
    ) {
        if (minutesBefore == null) {
            cancel(note.id, kind)
            return
        }

        val eventInstant = Instant.ofEpochMilli(event.start)
        val zone = runCatching { ZoneId.of(event.timeZone) }.getOrNull()
        val reminderInstant = zone?.let {
            eventInstant.atZone(it).minusMinutes(minutesBefore.toLong()).toInstant()
        } ?: eventInstant.minus(Duration.ofMinutes(minutesBefore.toLong()))
        val triggerAtMillis = reminderInstant.toEpochMilli()
        val now = System.currentTimeMillis()
        if (triggerAtMillis <= now) {
            Log.d(
                TAG,
                "scheduleReminder: skipping past reminder noteId=${note.id} kind=${kind} triggerAt=${triggerAtMillis} now=${now}"
            )
            cancel(note.id, kind)
            return
        }

        val existing = existingPendingIntent(note.id, kind)
        if (existing != null) {
            manager.cancel(existing)
            existing.cancel()
        }

        val canUseExact = canScheduleExactAlarms(context)
        val fallbackToNotification = when (kind) {
            ReminderPayload.Kind.ALARM -> !canUseExact
            ReminderPayload.Kind.REMINDER -> true
        }
        val payload = ReminderPayload.fromNote(
            note = note,
            minutesBeforeStart = minutesBefore,
            fallbackToNotification = fallbackToNotification,
            kind = kind,
        )
        val pendingIntent = ReminderAlarmService.createAlarmPendingIntent(context, payload)
        if (canUseExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
        Log.d(
            TAG,
            "scheduleReminder: scheduled noteId=${note.id} kind=${kind} reschedule=${existing != null} triggerAt=${triggerAtMillis} minutesBefore=${minutesBefore} fallback=${fallbackToNotification}"
        )
    }

    private fun cancel(noteId: Long, kind: ReminderPayload.Kind) {
        val manager = alarmManager ?: return
        val existing = existingPendingIntent(noteId, kind)
        if (existing != null) {
            manager.cancel(existing)
            existing.cancel()
            Log.d(TAG, "cancel: cancelled noteId=${noteId} kind=${kind}")
        } else {
            Log.d(TAG, "cancel: no existing alarm for noteId=${noteId} kind=${kind}")
        }
    }

    private fun existingPendingIntent(noteId: Long, kind: ReminderPayload.Kind): PendingIntent? {
        val intent = ReminderAlarmReceiver.createBaseIntent(context, noteId, kind)
        return PendingIntent.getBroadcast(
            context,
            requestCode(noteId, kind),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(noteId: Long, kind: ReminderPayload.Kind): Int {
        return (noteId.hashCode() * 31) + kind.ordinal
    }

    companion object {
        private const val TAG = "NoteAlarmScheduler"

        fun canScheduleExactAlarms(context: Context): Boolean {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
            } else {
                true
            }
        }
    }
}
