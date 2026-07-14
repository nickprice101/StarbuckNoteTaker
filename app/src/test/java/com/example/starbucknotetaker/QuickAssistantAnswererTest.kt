package com.example.starbucknotetaker

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAssistantAnswererTest {

    @Test
    fun answersCapitalQuestionsWithoutModel() {
        val answer = QuickAssistantAnswerer.answer("What is the capital of Iran?")

        assertTrue(answer?.answer.orEmpty().contains("Tehran"))
    }

    @Test
    fun answersSimpleArithmeticWithoutModel() {
        val answer = QuickAssistantAnswerer.answer("what is 12 / 4?")

        assertTrue(answer?.answer.orEmpty().contains("= 3"))
    }

    @Test
    fun answersDateQuestionsFromDeviceClock() {
        val now = ZonedDateTime.of(2026, 7, 14, 9, 30, 0, 0, ZoneId.of("America/New_York"))

        val answer = QuickAssistantAnswerer.answer("what is the date?", now = now)

        assertTrue(answer?.answer.orEmpty().contains("Tuesday, July 14, 2026"))
    }

    @Test
    fun answersNoteWordCountQuestions() {
        val answer = QuickAssistantAnswerer.answer(
            question = "how many words are in this note?",
            noteContext = "Alpha beta gamma delta.",
        )

        assertTrue(answer?.answer.orEmpty().contains("4 words"))
    }

    @Test
    fun leavesCurrentInformationForLookupOrModelPath() {
        val answer = QuickAssistantAnswerer.answer("what is the latest Android version?")

        assertNull(answer)
    }
}
