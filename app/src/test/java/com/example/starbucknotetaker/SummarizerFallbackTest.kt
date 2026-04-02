package com.example.starbucknotetaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun normalizeForModelInputFlattensTitleAndBody() {
        val input = "Title: Weekend groceries\n\nBuy oat milk, spinach, and eggs"

        val normalized = Summarizer.normalizeForModelInput(input)

        assertEquals("Weekend groceries: Buy oat milk, spinach, and eggs", normalized)
    }

    @Test
    fun normalizeForModelInputRemovesTitlePrefixWhenBodyMissing() {
        val input = "Title: Machine maintenance SOP"

        val normalized = Summarizer.normalizeForModelInput(input)

        assertEquals("Machine maintenance SOP", normalized)
    }

    @Test
    fun tokenizeForModelInputPadsAndTruncatesTo120Tokens() {
        val vocabulary = TokenizerVocabulary.from(listOf("[PAD]", "[UNK]", "travel", "prep", "book"))
        val input = List(130) { if (it % 2 == 0) "travel" else "book" }.joinToString(" ")

        val tokenIds = Summarizer.tokenizeForModelInput(input, vocabulary)

        assertEquals(120, tokenIds.size)
        assertTrue(tokenIds.all { it == 2 || it == 4 })
    }

    @Test
    fun tokenizeForModelInputUsesUnknownTokenForMissingWords() {
        val vocabulary = TokenizerVocabulary.from(listOf("[PAD]", "[UNK]", "travel", "prep"))

        val tokenIds = Summarizer.tokenizeForModelInput("travel espresso prep", vocabulary)

        assertEquals(2, tokenIds[0])
        assertEquals(1, tokenIds[1])
        assertEquals(3, tokenIds[2])
    }
}
