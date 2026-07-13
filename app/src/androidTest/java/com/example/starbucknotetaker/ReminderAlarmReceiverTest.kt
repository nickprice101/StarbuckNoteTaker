package com.example.starbucknotetaker

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        grantNotificationPermissionIfNeeded()
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
            kind = ReminderPayload.Kind.ALARM,
        )
        val intent = ReminderAlarmReceiver.createIntent(context, payload)
        ReminderAlarmReceiver().onReceive(context, intent)
        val active = notificationManager.activeNotifications
        assertTrue(active.any { it.id == payload.requestCode() })
    }

    private fun grantNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }
}
