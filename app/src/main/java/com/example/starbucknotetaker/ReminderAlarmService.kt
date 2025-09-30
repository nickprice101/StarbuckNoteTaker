package com.example.starbucknotetaker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

class ReminderAlarmService : Service() {
    private var ringtone: Ringtone? = null
    private var currentPayload: ReminderPayload? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        return when (action) {
            ACTION_DISMISS -> {
                stopAlarm()
                START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                val payload = ReminderPayload.fromIntent(intent) ?: currentPayload
                if (payload != null) {
                    scheduleSnooze(payload)
                }
                stopAlarm()
                START_NOT_STICKY
            }
            else -> {
                val payload = ReminderPayload.fromIntent(intent)
                if (payload == null) {
                    Log.w(TAG, "onStartCommand: missing payload")
                    stopSelfResult(startId)
                    START_NOT_STICKY
                } else {
                    startAlarm(payload)
                    START_STICKY
                }
            }
        }
    }

    override fun onDestroy() {
        stopRingtone()
        super.onDestroy()
    }

    private fun startAlarm(payload: ReminderPayload) {
        currentPayload = payload
        ensureChannel()
        val notification = buildNotification(payload)
        startForeground(payload.noteId.hashCode(), notification)
        startRingtone()
    }

    private fun stopAlarm() {
        stopRingtone()
        stopForegroundCompat()
        stopSelf()
    }

    private fun startRingtone() {
        val ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (ringtoneUri == null) {
            Log.w(TAG, "startRingtone: missing ringtone URI")
            return
        }
        ringtone = RingtoneManager.getRingtone(this, ringtoneUri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isLooping = true
            }
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            play()
        }
    }

    private fun stopRingtone() {
        runCatching { ringtone?.stop() }.onFailure { throwable ->
            Log.w(TAG, "stopRingtone: failed", throwable)
        }
        ringtone = null
    }

    private fun scheduleSnooze(payload: ReminderPayload) {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(SNOOZE_MINUTES.toLong())
        val snoozedPayload = payload.copy(fallbackToNotification = payload.fallbackToNotification)
        val pendingIntent = createAlarmPendingIntent(this, snoozedPayload)
        if (NoteAlarmScheduler.canScheduleExactAlarms(this)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        NotificationInterruptionManager.runOrQueue {
            Toast.makeText(
                applicationContext,
                getString(R.string.reminder_alarm_snoozed, SNOOZE_MINUTES),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(payload: ReminderPayload): Notification {
        val fullScreenIntent = ReminderAlarmActivity.createIntent(this, payload).let { intent ->
            PendingIntent.getActivity(
                this,
                payload.noteId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val dismissIntent = createServicePendingIntent(this, ACTION_DISMISS, payload)
        val snoozeIntent = createServicePendingIntent(this, ACTION_SNOOZE, payload)
        val message = getString(R.string.reminder_alarm_body)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_notepad)
            .setContentTitle(getString(R.string.reminder_alarm_title, payload.title))
            .setContentText(message)
            .setFullScreenIntent(fullScreenIntent, true)
            .setDeleteIntent(dismissIntent)
            .addAction(
                0,
                getString(R.string.reminder_alarm_action_snooze, SNOOZE_MINUTES),
                snoozeIntent,
            )
            .addAction(0, getString(R.string.reminder_alarm_action_dismiss), dismissIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.reminder_alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.reminder_alarm_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ReminderAlarmService"
        private const val CHANNEL_ID = "note-alarm-fullscreen"
        private const val ACTION_DISMISS = "com.example.starbucknotetaker.action.DISMISS_ALARM"
        private const val ACTION_SNOOZE = "com.example.starbucknotetaker.action.SNOOZE_ALARM"
        const val SNOOZE_MINUTES = 10

        fun start(context: Context, payload: ReminderPayload) {
            val intent = Intent(context, ReminderAlarmService::class.java).apply {
                action = "com.example.starbucknotetaker.action.START_ALARM"
                payload.fillIntent(this)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun dismiss(context: Context, payload: ReminderPayload) {
            ContextCompat.startForegroundService(context, createServiceIntent(context, ACTION_DISMISS, payload))
        }

        fun snooze(context: Context, payload: ReminderPayload) {
            ContextCompat.startForegroundService(context, createServiceIntent(context, ACTION_SNOOZE, payload))
        }

        private fun createServiceIntent(context: Context, action: String, payload: ReminderPayload): Intent {
            val intent = Intent(context, ReminderAlarmService::class.java).apply {
                this.action = action
                payload.fillIntent(this)
            }
            return intent
        }

        private fun createServicePendingIntent(context: Context, action: String, payload: ReminderPayload): PendingIntent {
            val intent = createServiceIntent(context, action, payload)
            val requestCode = (payload.noteId.hashCode() xor action.hashCode())
            return PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        internal fun createAlarmPendingIntent(context: Context, payload: ReminderPayload): PendingIntent {
            val intent = ReminderAlarmReceiver.createIntent(context, payload)
            return PendingIntent.getBroadcast(
                context,
                payload.requestCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
