package com.example.starbucknotetaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NoteReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1L)
        if (noteId <= 0) return Result.failure()
        val title = inputData.getString(KEY_NOTE_TITLE) ?: return Result.failure()
        val summary = inputData.getString(KEY_NOTE_SUMMARY).orEmpty()
        val eventStart = inputData.getLong(KEY_EVENT_START, -1L)
        if (eventStart <= 0) return Result.failure()
        val allDay = inputData.getBoolean(KEY_EVENT_ALL_DAY, false)
        val timeZoneId = inputData.getString(KEY_EVENT_TIME_ZONE) ?: ZoneId.systemDefault().id
        val location = inputData.getString(KEY_EVENT_LOCATION).takeIf { !it.isNullOrBlank() }
        val isLocked = inputData.getBoolean(KEY_NOTE_LOCKED, false)
        val reminderMinutes = inputData.getInt(KEY_REMINDER_MINUTES, 0)

        showNotification(
            noteId = noteId,
            title = title,
            summary = summary,
            eventStart = eventStart,
            allDay = allDay,
            timeZoneId = timeZoneId,
            location = location,
            isLocked = isLocked,
            reminderMinutes = reminderMinutes,
        )
        return Result.success()
    }

    private fun showNotification(
        noteId: Long,
        title: String,
        summary: String,
        eventStart: Long,
        allDay: Boolean,
        timeZoneId: String,
        location: String?,
        isLocked: Boolean,
        reminderMinutes: Int,
    ) {
        val context = applicationContext
        ensureChannel(context)

        val zone = runCatching { ZoneId.of(timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val start = Instant.ofEpochMilli(eventStart).atZone(zone)
        val formatter = if (allDay) DATE_FORMAT else DATE_TIME_FORMAT
        val timeText = start.format(formatter)

        val detailText = if (isLocked) {
            context.getString(R.string.reminder_notification_locked_body)
        } else {
            buildString {
                append(timeText)
                if (!location.isNullOrBlank()) {
                    append(" • ")
                    append(location)
                }
                val trimmedSummary = summary.takeIf { it.isNotBlank() }
                if (!trimmedSummary.isNullOrBlank()) {
                    append('\n')
                    append(trimmedSummary)
                }
            }.ifBlank { timeText }
        }
        val collapsedText = if (isLocked) {
            context.getString(R.string.reminder_notification_locked_body)
        } else {
            detailText.lineSequence().firstOrNull().orEmpty().ifBlank { detailText }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_VIEW_NOTE_FROM_REMINDER
            putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
        }
        val pendingIntent = TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent)
            getPendingIntent(
                noteId.hashCode(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } ?: return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notepad)
            .setContentTitle(context.getString(R.string.reminder_notification_title, title))
            .setContentText(collapsedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(eventStart)
            .setShowWhen(true)
            .setContentIntent(pendingIntent)

        if (!isLocked && reminderMinutes > 0) {
            builder.setSubText(
                context.getString(
                    R.string.reminder_notification_subtext,
                    formatReminderOffsetMinutes(reminderMinutes),
                )
            )
        }

        NotificationManagerCompat.from(context).notify(noteId.hashCode(), builder.build())
    }

    companion object {
        const val KEY_NOTE_ID = "note_id"
        const val KEY_NOTE_TITLE = "note_title"
        const val KEY_NOTE_SUMMARY = "note_summary"
        const val KEY_EVENT_START = "event_start"
        const val KEY_EVENT_TIME_ZONE = "event_time_zone"
        const val KEY_EVENT_ALL_DAY = "event_all_day"
        const val KEY_EVENT_LOCATION = "event_location"
        const val KEY_NOTE_LOCKED = "note_locked"
        const val KEY_REMINDER_MINUTES = "reminder_minutes"

        private const val CHANNEL_ID = "note-reminders"
        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")

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
}
