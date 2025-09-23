package com.example.starbucknotetaker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.starbucknotetaker.NoteAlarmScheduler

fun requestExactAlarmPermission(
    context: Context,
    updateCanSchedule: (Boolean) -> Unit,
    onNeedsPermission: (Intent) -> Unit,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        updateCanSchedule(true)
        return false
    }
    val canSchedule = NoteAlarmScheduler.canScheduleExactAlarms(context)
    updateCanSchedule(canSchedule)
    if (canSchedule) {
        return false
    }
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    onNeedsPermission(intent)
    return true
}
