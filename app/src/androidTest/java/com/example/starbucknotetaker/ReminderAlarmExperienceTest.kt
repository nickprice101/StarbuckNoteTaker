package com.example.starbucknotetaker

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ActivityScenarioRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderAlarmExperienceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val initialPayload = ReminderPayload(
        noteId = 42L,
        title = "Morning Sync",
        summary = "Discuss project milestones",
        eventStart = System.currentTimeMillis() + 60_000,
        timeZoneId = "UTC",
        allDay = false,
        location = "Conference Room",
        isLocked = false,
        reminderMinutes = 15,
        fallbackToNotification = false,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule(
        ActivityScenarioRule(
            ReminderAlarmActivity.createIntent(context, initialPayload)
        )
    )

    @Test
    fun coldStartDisplaysReminderDetails() {
        composeRule.onNodeWithText(initialPayload.title).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.reminder_alarm_action_dismiss)).performClick()
        composeRule.waitUntil {
            composeRule.activityRule.scenario.state == Lifecycle.State.DESTROYED
        }
    }

    @Test
    fun newIntentUpdatesContent() {
        val updatedPayload = initialPayload.copy(
            noteId = 99L,
            title = "Afternoon Brief",
            summary = "Review status",
        )
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onNewIntent(ReminderAlarmActivity.createIntent(activity, updatedPayload))
        }
        composeRule.onNodeWithText(updatedPayload.title).assertExists()
    }
}
