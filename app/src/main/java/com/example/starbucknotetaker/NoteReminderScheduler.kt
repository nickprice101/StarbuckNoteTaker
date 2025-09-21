package com.example.starbucknotetaker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class NoteReminderScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun scheduleIfNeeded(note: Note) {
        val event = note.event
        val reminderMinutes = event?.reminderMinutesBeforeStart
        if (event == null || reminderMinutes == null) {
            cancel(note.id)
            return
        }
        val eventInstant = Instant.ofEpochMilli(event.start)
        val zone = runCatching { ZoneId.of(event.timeZone) }.getOrNull()
        val reminderInstant = zone?.let {
            eventInstant.atZone(it).minusMinutes(reminderMinutes.toLong()).toInstant()
        } ?: eventInstant.minus(Duration.ofMinutes(reminderMinutes.toLong()))
        val nowInstant = Instant.now()
        val delay = Duration.between(nowInstant, reminderInstant)
        if (delay <= Duration.ZERO) {
            cancel(note.id)
            return
        }
        val data = workDataOf(
            NoteReminderWorker.KEY_NOTE_ID to note.id,
            NoteReminderWorker.KEY_NOTE_TITLE to note.title,
            NoteReminderWorker.KEY_NOTE_SUMMARY to note.summary,
            NoteReminderWorker.KEY_EVENT_START to event.start,
            NoteReminderWorker.KEY_EVENT_TIME_ZONE to event.timeZone,
            NoteReminderWorker.KEY_EVENT_ALL_DAY to event.allDay,
            NoteReminderWorker.KEY_EVENT_LOCATION to (event.location ?: ""),
            NoteReminderWorker.KEY_NOTE_LOCKED to note.isLocked,
            NoteReminderWorker.KEY_REMINDER_MINUTES to reminderMinutes,
        )

        val request = OneTimeWorkRequestBuilder<NoteReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag(workTag(note.id))
            .build()

        workManager.enqueueUniqueWork(uniqueWorkName(note.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(noteId: Long) {
        workManager.cancelUniqueWork(uniqueWorkName(noteId))
    }

    fun syncNotes(notes: List<Note>) {
        notes.forEach { note -> scheduleIfNeeded(note) }
    }

    private fun uniqueWorkName(noteId: Long): String = "note-reminder-$noteId"

    private fun workTag(noteId: Long): String = "note-reminder-tag-$noteId"
}
