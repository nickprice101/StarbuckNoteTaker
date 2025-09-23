package com.example.starbucknotetaker.ui

import com.example.starbucknotetaker.Note
import com.example.starbucknotetaker.NoteEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class NoteDetailScreenTest {

    @Test
    fun `event summary splits location into name and address`() {
        val event = NoteEvent(
            start = 1_700_000_000_000,
            end = 1_700_000_360_000,
            allDay = false,
            timeZone = "UTC",
            location = "Melkweg\nLijnbaansgracht 234A, Amsterdam",
            reminderMinutesBeforeStart = null,
        )

        val summary = invokeBuildEventSummary(event)
        val lines = summary.lines()
        val locationIndex = lines.indexOfFirst { it.startsWith("Location:") }
        assertTrue("Location line not found in summary: $summary", locationIndex >= 0)
        assertEquals("Location: Melkweg", lines[locationIndex])
        assertTrue(
            "Expected address line after location but was: ${lines.getOrNull(locationIndex + 1)}",
            lines.getOrNull(locationIndex + 1)?.trim() == "Lijnbaansgracht 234A, Amsterdam"
        )
    }

    @Test
    fun `share text uses formatted event summary`() {
        val event = NoteEvent(
            start = 1_700_000_000_000,
            end = 1_700_000_360_000,
            allDay = false,
            timeZone = "UTC",
            location = "Melkweg\nLijnbaansgracht 234A, Amsterdam",
            reminderMinutesBeforeStart = null,
        )
        val note = Note(
            title = "Concert",
            content = "",
            event = event,
        )

        val shareText = invokeBuildShareText(note)

        assertTrue(
            "Share text should contain the formatted location",
            shareText.contains("Location: Melkweg\nLijnbaansgracht 234A, Amsterdam")
        )
    }

    private fun invokeBuildEventSummary(event: NoteEvent): String {
        val method = buildEventSummaryMethod
        return method.invoke(null, event) as String
    }

    private fun invokeBuildShareText(note: Note): String {
        val method = buildShareTextMethod
        return method.invoke(null, note) as String
    }

    private val buildEventSummaryMethod: Method by lazy {
        val clazz = Class.forName("com.example.starbucknotetaker.ui.NoteDetailScreenKt")
        clazz.getDeclaredMethod("buildEventSummary", NoteEvent::class.java).apply {
            isAccessible = true
        }
    }

    private val buildShareTextMethod: Method by lazy {
        val clazz = Class.forName("com.example.starbucknotetaker.ui.NoteDetailScreenKt")
        clazz.getDeclaredMethod("buildShareText", Note::class.java).apply {
            isAccessible = true
        }
    }
}
