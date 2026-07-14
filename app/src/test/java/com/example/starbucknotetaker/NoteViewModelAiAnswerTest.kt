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
    fun askQuestionCreatesProgressAnswerNoteImmediatelyForComplexQuestion() {
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

        viewModel.askQuestion(source.id, "explain how this note should be expanded into a study plan")

        val answer = viewModel.notes.firstOrNull { it.title.startsWith("Answer") }
        assertNotNull(answer)
        requireNotNull(answer)
        assertTrue(answer.content.contains("Question:"))
        assertTrue(answer.content.contains("study plan"))
        assertTrue(answer.content.contains("Live update:"))
        assertTrue(answer.content.contains("Received question"))
        assertTrue(answer.content.contains("Checking for quick answer"))
        assertTrue(answer.content.contains("Starting assistant service"))
        assertEquals("AI answer in progress", answer.summary)
    }

    @Test
    fun askQuestionAnswersSimpleCapitalQuestionImmediately() {
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
        assertTrue(answer.content.contains("Answer:"))
        assertTrue(answer.content.contains("Tehran"))
        assertTrue(answer.summary.contains("Tehran"))
    }

    @Test
    fun askQuestionRewriteRequestFormatsSourceNoteImmediately() {
        val viewModel = NoteViewModel(SavedStateHandle())
        viewModel.loadNotes(appContext, "1234")
        viewModel.addNote(
            title = "Client Meeting",
            content = "client meeting notes: call alex tomorrow, update launch doc, send recap to team",
            styledContent = RichTextDocument.fromPlainText(
                "client meeting notes: call alex tomorrow, update launch doc, send recap to team",
            ),
            images = emptyList(),
            files = emptyList(),
            linkPreviews = emptyList(),
            skipAiSummary = true,
        )
        val source = viewModel.notes.first { it.title == "Client Meeting" }

        viewModel.askQuestion(source.id, "rewrite and format this note in a more professional way")

        val updated = viewModel.getNoteById(source.id)
        assertNotNull(updated)
        requireNotNull(updated)
        assertTrue(updated.content.contains("Overview"))
        assertTrue(updated.content.contains("Action Items"))
        assertTrue(updated.content.contains("- Update launch doc."))
        assertTrue(updated.summary.contains("AI rewrite"))
        assertTrue(viewModel.notes.none { it.title.startsWith("Answer") })
    }

    @Test
    fun rewriteNoteAppliesQuickFormattedDraftImmediately() {
        val viewModel = NoteViewModel(SavedStateHandle())
        viewModel.loadNotes(appContext, "1234")
        viewModel.addNote(
            title = "Planning",
            content = "todo: review budget; schedule vendor call; prepare board update",
            styledContent = RichTextDocument.fromPlainText(
                "todo: review budget; schedule vendor call; prepare board update",
            ),
            images = emptyList(),
            files = emptyList(),
            linkPreviews = emptyList(),
            skipAiSummary = true,
        )
        val source = viewModel.notes.first { it.title == "Planning" }

        viewModel.rewriteNote(source.id)

        val updated = viewModel.getNoteById(source.id)
        assertNotNull(updated)
        requireNotNull(updated)
        assertTrue(updated.content.contains("Overview"))
        assertTrue(updated.content.contains("Action Items"))
        assertTrue(updated.content.contains("- Schedule vendor call."))
        assertTrue(updated.summary.contains("AI rewrite"))
    }
}
