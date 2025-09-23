package com.example.starbucknotetaker

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderAlarmReceiverTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun fallbackNotificationIsShown() {
        val payload = ReminderPayload(
            noteId = 123L,
            title = "Fallback",
            summary = "",
            eventStart = System.currentTimeMillis(),
            timeZoneId = "UTC",
            allDay = false,
            location = null,
            isLocked = false,
            reminderMinutes = 10,
            fallbackToNotification = true,
        )
        val intent = ReminderAlarmReceiver.createIntent(context, payload)
        ReminderAlarmReceiver().onReceive(context, intent)
        val active = notificationManager.activeNotifications
        assertTrue(active.any { it.id == payload.noteId.hashCode() })
    }
}
