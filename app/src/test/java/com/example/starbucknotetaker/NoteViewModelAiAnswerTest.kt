package com.example.starbucknotetaker

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.starbucknotetaker.richtext.RichTextDocument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteViewModelAiAnswerTest {
    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        File(appContext.filesDir, "notes.enc").delete()
    }

    @Test
    fun askQuestionCreatesProgressAnswerNoteImmediately() {
        val viewModel = NoteViewModel(SavedStateHandle())
        viewModel.loadNotes(appContext, "1234")
        viewModel.addNote(
            title = "Geography",
            content = "General question note",
            styledContent = RichTextDocument.fromPlainText("General question note"),
            images = emptyList(),
            files = emptyList(),
            linkPreviews = emptyList(),
            skipAiSummary = true,
        )
        val source = viewModel.notes.first { it.title == "Geography" }

        viewModel.askQuestion(source.id, "what is the capital of Iran?")

        val answer = viewModel.notes.firstOrNull { it.title.startsWith("Answer") }
        assertNotNull(answer)
        requireNotNull(answer)
        assertTrue(answer.content.contains("Question:"))
        assertTrue(answer.content.contains("what is the capital of Iran?"))
        assertTrue(answer.content.contains("Status:"))
        assertTrue(answer.content.contains("Starting on-device assistant"))
        assertEquals("AI answer in progress", answer.summary)
    }
}
