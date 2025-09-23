package com.example.starbucknotetaker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOW_NOTE_REMINDER) {
            return
        }
        val payload = ReminderPayload.fromIntent(intent)
        if (payload == null) {
            Log.w(TAG, "onReceive: missing payload")
            return
        }
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val wakeLock = acquireWakeLock(appContext, payload.noteId)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                showNotification(appContext, payload)
                Log.d(TAG, "onReceive: displayed notification noteId=${payload.noteId}")
            } catch (t: Throwable) {
                Log.e(TAG, "onReceive: failed to display notification", t)
            } finally {
                wakeLock?.let { lock ->
                    if (lock.isHeld) {
                        lock.release()
                    }
                }
                pendingResult.finish()
            }
        }
    }

    private fun acquireWakeLock(context: Context, noteId: Long): PowerManager.WakeLock? {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return null
        return runCatching {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${WAKE_LOCK_TAG}:${noteId}").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure { throwable ->
            Log.w(TAG, "acquireWakeLock: failed", throwable)
        }.getOrNull()
    }

    private fun showNotification(context: Context, payload: ReminderPayload) {
        ensureChannel(context)

        val zone = runCatching { ZoneId.of(payload.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val start = Instant.ofEpochMilli(payload.eventStart).atZone(zone)
        val formatter = if (payload.allDay) DATE_FORMAT else DATE_TIME_FORMAT
        val timeText = start.format(formatter)

        val detailText = if (payload.isLocked) {
            context.getString(R.string.reminder_notification_locked_body)
        } else {
            buildString {
                append(timeText)
                if (!payload.location.isNullOrBlank()) {
                    append(" • ")
                    append(payload.location)
                }
                val trimmedSummary = payload.summary.takeIf { it.isNotBlank() }
                if (!trimmedSummary.isNullOrBlank()) {
                    append('\n')
                    append(trimmedSummary)
                }
            }.ifBlank { timeText }
        }
        val collapsedText = if (payload.isLocked) {
            context.getString(R.string.reminder_notification_locked_body)
        } else {
            detailText.lineSequence().firstOrNull().orEmpty().ifBlank { detailText }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_VIEW_NOTE_FROM_REMINDER
            putExtra(MainActivity.EXTRA_NOTE_ID, payload.noteId)
        }
        val pendingIntent = TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent)
            getPendingIntent(
                payload.noteId.hashCode(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } ?: return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notepad)
            .setContentTitle(context.getString(R.string.reminder_notification_title, payload.title))
            .setContentText(collapsedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(payload.eventStart)
            .setShowWhen(true)
            .setContentIntent(pendingIntent)

        if (!payload.isLocked && payload.reminderMinutes > 0) {
            builder.setSubText(
                context.getString(
                    R.string.reminder_notification_subtext,
                    formatReminderOffsetMinutes(payload.reminderMinutes),
                )
            )
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "showNotification: missing POST_NOTIFICATIONS permission")
            return
        }

        NotificationManagerCompat.from(context).notify(payload.noteId.hashCode(), builder.build())
    }

    companion object {
        private const val TAG = "ReminderAlarmReceiver"
        const val ACTION_SHOW_NOTE_REMINDER = "com.example.starbucknotetaker.action.SHOW_NOTE_REMINDER"
        private const val EXTRA_NOTE_ID = "extra_note_id"
        private const val EXTRA_NOTE_TITLE = "extra_note_title"
        private const val EXTRA_NOTE_SUMMARY = "extra_note_summary"
        private const val EXTRA_EVENT_START = "extra_event_start"
        private const val EXTRA_EVENT_TIME_ZONE = "extra_event_time_zone"
        private const val EXTRA_EVENT_ALL_DAY = "extra_event_all_day"
        private const val EXTRA_EVENT_LOCATION = "extra_event_location"
        private const val EXTRA_NOTE_LOCKED = "extra_note_locked"
        private const val EXTRA_REMINDER_MINUTES = "extra_reminder_minutes"
        private const val CHANNEL_ID = "note-reminders"
        private const val WAKE_LOCK_TAG = "StarbuckNoteTaker:ReminderWakeLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")

        fun createIntent(context: Context, note: Note): Intent {
            val event = note.event ?: throw IllegalArgumentException("Note must have event to schedule reminder")
            return Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_SHOW_NOTE_REMINDER
                putExtrasFrom(event, note)
            }
        }

        fun createBaseIntent(context: Context, noteId: Long): Intent {
            return Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_SHOW_NOTE_REMINDER
                putExtra(EXTRA_NOTE_ID, noteId)
            }
        }

        private fun Intent.putExtrasFrom(event: NoteEvent, note: Note) {
            putExtra(EXTRA_NOTE_ID, note.id)
            putExtra(EXTRA_NOTE_TITLE, note.title)
            putExtra(EXTRA_NOTE_SUMMARY, note.summary)
            putExtra(EXTRA_EVENT_START, event.start)
            putExtra(EXTRA_EVENT_TIME_ZONE, event.timeZone)
            putExtra(EXTRA_EVENT_ALL_DAY, event.allDay)
            putExtra(EXTRA_EVENT_LOCATION, event.location)
            putExtra(EXTRA_NOTE_LOCKED, note.isLocked)
            putExtra(EXTRA_REMINDER_MINUTES, event.reminderMinutesBeforeStart ?: 0)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.reminder_notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.reminder_notification_channel_description)
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }

    private data class ReminderPayload(
        val noteId: Long,
        val title: String,
        val summary: String,
        val eventStart: Long,
        val timeZoneId: String,
        val allDay: Boolean,
        val location: String?,
        val isLocked: Boolean,
        val reminderMinutes: Int,
    ) {
        companion object {
            fun fromIntent(intent: Intent): ReminderPayload? {
                val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
                if (noteId <= 0) return null
                val title = intent.getStringExtra(EXTRA_NOTE_TITLE) ?: return null
                val summary = intent.getStringExtra(EXTRA_NOTE_SUMMARY).orEmpty()
                val eventStart = intent.getLongExtra(EXTRA_EVENT_START, -1L)
                if (eventStart <= 0) return null
                val timeZoneId = intent.getStringExtra(EXTRA_EVENT_TIME_ZONE) ?: ZoneId.systemDefault().id
                val allDay = intent.getBooleanExtra(EXTRA_EVENT_ALL_DAY, false)
                val location = intent.getStringExtra(EXTRA_EVENT_LOCATION).takeIf { !it.isNullOrBlank() }
                val isLocked = intent.getBooleanExtra(EXTRA_NOTE_LOCKED, false)
                val reminderMinutes = intent.getIntExtra(EXTRA_REMINDER_MINUTES, 0)
                return ReminderPayload(
                    noteId = noteId,
                    title = title,
                    summary = summary,
                    eventStart = eventStart,
                    timeZoneId = timeZoneId,
                    allDay = allDay,
                    location = location,
                    isLocked = isLocked,
                    reminderMinutes = reminderMinutes,
                )
            }
        }
    }
}
