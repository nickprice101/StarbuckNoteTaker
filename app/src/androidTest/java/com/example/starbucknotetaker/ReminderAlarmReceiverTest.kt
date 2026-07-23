package com.example.starbucknotetaker

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
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
        assertTrue(
            "Expected fallback notification ${payload.requestCode()} to be shown",
            waitForNotification(payload.requestCode()),
        )
    }

    private fun waitForNotification(notificationId: Int): Boolean {
        val timeoutAt = SystemClock.uptimeMillis() + NOTIFICATION_TIMEOUT_MS
        do {
            if (notificationManager.activeNotifications.any { it.id == notificationId }) {
                return true
            }
            SystemClock.sleep(NOTIFICATION_POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        return false
    }

    private fun grantNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    private companion object {
        const val NOTIFICATION_TIMEOUT_MS = 2_000L
        const val NOTIFICATION_POLL_INTERVAL_MS = 50L
    }
}
