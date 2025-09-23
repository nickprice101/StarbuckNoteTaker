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
        try {
            if (payload.fallbackToNotification) {
                showNotification(appContext, payload)
                Log.d(TAG, "onReceive: fallback notification shown noteId=${payload.noteId}")
            } else {
                ReminderAlarmService.start(appContext, payload)
                Log.d(TAG, "onReceive: started full-screen alarm noteId=${payload.noteId}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onReceive: failed to handle reminder", t)
        } finally {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
            }
            pendingResult.finish()
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
        private const val CHANNEL_ID = "note-reminders"
        private const val WAKE_LOCK_TAG = "StarbuckNoteTaker:ReminderWakeLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")

        fun createIntent(context: Context, note: Note, fallbackToNotification: Boolean): Intent {
            val payload = ReminderPayload.fromNote(note, fallbackToNotification)
            return createIntent(context, payload)
        }

        fun createIntent(context: Context, payload: ReminderPayload): Intent {
            return Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_SHOW_NOTE_REMINDER
                payload.fillIntent(this)
            }
        }

        fun createBaseIntent(context: Context, noteId: Long): Intent {
            return Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_SHOW_NOTE_REMINDER
                putExtra(ReminderPayload.EXTRA_NOTE_ID, noteId)
            }
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

}
