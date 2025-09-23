package com.example.starbucknotetaker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.starbucknotetaker.ui.StarbuckNoteTakerTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReminderAlarmActivity : ComponentActivity() {
    private var payloadState = mutableStateOf<ReminderPayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureForLockscreen()
        payloadState.value = ReminderPayload.fromIntent(intent)
        setContent {
            val payload = payloadState.value
            StarbuckNoteTakerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (payload == null) {
                        MissingPayloadContent(onDismiss = { finish() })
                    } else {
                        ReminderAlarmContent(
                            payload = payload,
                            onDismiss = { dismissAlarm(payload) },
                            onSnooze = { snoozeAlarm(payload) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        payloadState.value = ReminderPayload.fromIntent(intent)
    }

    @VisibleForTesting
    internal fun handleNewIntentForTest(intent: Intent) {
        onNewIntent(intent)
    }

    private fun configureForLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun dismissAlarm(payload: ReminderPayload) {
        ReminderAlarmService.dismiss(applicationContext, payload)
        finish()
    }

    private fun snoozeAlarm(payload: ReminderPayload) {
        ReminderAlarmService.snooze(applicationContext, payload)
        finish()
    }

    companion object {
        fun createIntent(context: Context, payload: ReminderPayload): Intent {
            return Intent(context, ReminderAlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                payload.fillIntent(this)
            }
        }
    }
}

@Composable
private fun ReminderAlarmContent(
    payload: ReminderPayload,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    val zone = remember(payload.timeZoneId) {
        runCatching { ZoneId.of(payload.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
    }
    val startText = remember(payload.eventStart, payload.allDay) {
        val instant = Instant.ofEpochMilli(payload.eventStart)
        val formatter = if (payload.allDay) DATE_FORMAT else DATE_TIME_FORMAT
        instant.atZone(zone).format(formatter)
    }
    val locationText = payload.location?.takeIf { it.isNotBlank() }
    val summary = payload.summary.takeIf { it.isNotBlank() && !payload.isLocked }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.reminder_alarm_heading),
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = payload.title.ifBlank { stringResource(R.string.reminder_alarm_default_title) },
            style = MaterialTheme.typography.h4,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = startText,
            style = MaterialTheme.typography.h6,
            modifier = Modifier.fillMaxWidth(),
        )
        if (locationText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = locationText,
                style = MaterialTheme.typography.subtitle1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (summary != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.body1,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (payload.isLocked) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.reminder_alarm_locked_content),
                style = MaterialTheme.typography.body1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        AlarmActions(onDismiss = onDismiss, onSnooze = onSnooze)
    }
}

@Composable
private fun AlarmActions(
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            Text(text = stringResource(R.string.reminder_alarm_action_dismiss))
        }
        Button(
            onClick = onSnooze,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.reminder_alarm_action_snooze,
                    ReminderAlarmService.SNOOZE_MINUTES,
                ),
            )
        }
    }
}

@Composable
private fun MissingPayloadContent(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.reminder_alarm_missing_payload))
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onDismiss) {
            Text(text = stringResource(R.string.reminder_alarm_action_close))
        }
    }
}

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
