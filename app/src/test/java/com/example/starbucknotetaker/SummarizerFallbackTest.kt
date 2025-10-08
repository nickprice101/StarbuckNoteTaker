package com.example.starbucknotetaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SummarizerFallbackTest {
    @Test
    fun lightweightPreviewUsesFirstSentences() {
        val text = "Morning briefing. Follow-up on vendor contracts. Schedule retro."
        val preview = Summarizer.lightweightPreview(text)

        assertEquals("Morning briefing. Follow-up on vendor contracts.", preview)
    }

    @Test
    fun smartTruncateRespectsSentenceBoundary() {
        val longText = buildString {
            append("Weekly planning session covering roadmap adjustments and budget reallocations. ")
            append("Discuss hiring plans and staffing needs across teams.")
        }
        val truncated = Summarizer.smartTruncate(longText, maxLength = 80)

        assertEquals(
            "Weekly planning session covering roadmap adjustments and budget reallocations.",
            truncated
        )
    }

    @Test
    fun smartTruncateFallsBackToWordBoundary() {
        val truncated = Summarizer.smartTruncate("OneTwoThreeFourFive", maxLength = 10)

        assertEquals("OneTwoThre", truncated)
    }

    @Test
    fun lightweightPreviewTrimsWhitespace() {
        val text = "\n  Quick reminder about dentist appointment tomorrow morning.  \n"
        val preview = Summarizer.lightweightPreview(text)

        assertFalse(preview.startsWith(" "))
        assertEquals("Quick reminder about dentist appointment tomorrow morning.", preview)
    }
}
