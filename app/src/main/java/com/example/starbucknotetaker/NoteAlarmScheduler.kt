package com.example.starbucknotetaker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
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
        val reminderMinutes = event?.reminderMinutesBeforeStart
        if (event == null || reminderMinutes == null) {
            cancel(note.id)
            return
        }

        val eventInstant = Instant.ofEpochMilli(event.start)
        val zone = runCatching { ZoneId.of(event.timeZone) }.getOrNull()
        val reminderInstant = zone?.let {
            eventInstant.atZone(it).minusMinutes(reminderMinutes.toLong()).toInstant()
        } ?: eventInstant.minus(Duration.ofMinutes(reminderMinutes.toLong()))
        val triggerAtMillis = reminderInstant.toEpochMilli()
        val now = System.currentTimeMillis()
        if (triggerAtMillis <= now) {
            Log.d(
                TAG,
                "scheduleIfNeeded: skipping past reminder noteId=${note.id} triggerAt=${triggerAtMillis} now=${now}"
            )
            cancel(note.id)
            return
        }

        val existing = existingPendingIntent(note.id)
        if (existing != null) {
            manager.cancel(existing)
            existing.cancel()
        }

        val pendingIntent = createPendingIntent(note)
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        Log.d(
            TAG,
            "scheduleIfNeeded: scheduled noteId=${note.id} reschedule=${existing != null} triggerAt=${triggerAtMillis} minutesBefore=${reminderMinutes}"
        )
    }

    fun cancel(noteId: Long) {
        val manager = alarmManager ?: return
        val existing = existingPendingIntent(noteId)
        if (existing != null) {
            manager.cancel(existing)
            existing.cancel()
            Log.d(TAG, "cancel: cancelled noteId=${noteId}")
        } else {
            Log.d(TAG, "cancel: no existing alarm for noteId=${noteId}")
        }
    }

    fun syncNotes(notes: List<Note>) {
        notes.forEach { note -> scheduleIfNeeded(note) }
    }

    private fun createPendingIntent(note: Note): PendingIntent {
        val intent = ReminderAlarmReceiver.createIntent(context, note)
        return PendingIntent.getBroadcast(
            context,
            note.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun existingPendingIntent(noteId: Long): PendingIntent? {
        val intent = ReminderAlarmReceiver.createBaseIntent(context, noteId)
        return PendingIntent.getBroadcast(
            context,
            noteId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "NoteAlarmScheduler"
    }
}
