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
    fun askQuestionRewriteRequestCreatesCopyAndPreservesSource() {
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
        val originalContent = source.content

        val rewrittenId =
            viewModel.askQuestion(source.id, "rewrite and format this note in a more professional way")

        assertNotNull(rewrittenId)
        val original = viewModel.getNoteById(source.id)
        val rewritten = viewModel.getNoteById(requireNotNull(rewrittenId))
        assertEquals("Client Meeting", original?.title)
        assertEquals(originalContent, original?.content)
        assertNotNull(rewritten)
        requireNotNull(rewritten)
        assertTrue(rewritten.title.startsWith("Reformatted - Client Meeting"))
        assertTrue(rewritten.content.contains("Overview"))
        assertTrue(rewritten.content.contains("Action Items"))
        assertTrue(rewritten.content.contains("• Update launch doc."))
        assertTrue(rewritten.styledContent?.spans?.isNotEmpty() == true)
        assertTrue(viewModel.notes.none { it.title.startsWith("Answer") })
    }

    @Test
    fun rewriteNoteCreatesQuickFormattedCopyAndPreservesSource() {
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
        val originalContent = source.content

        val rewrittenId = viewModel.rewriteNote(source.id)

        assertNotNull(rewrittenId)
        val original = viewModel.getNoteById(source.id)
        val rewritten = viewModel.getNoteById(requireNotNull(rewrittenId))
        assertEquals("Planning", original?.title)
        assertEquals(originalContent, original?.content)
        assertNotNull(rewritten)
        requireNotNull(rewritten)
        assertTrue(rewritten.title.startsWith("Reformatted - Planning"))
        assertTrue(rewritten.content.contains("Overview"))
        assertTrue(rewritten.content.contains("Action Items"))
        assertTrue(rewritten.content.contains("• Schedule vendor call."))
        assertTrue(rewritten.styledContent?.spans?.isNotEmpty() == true)
    }

    @Test
    fun rewriteNoteCanEditCurrentNoteWithoutCreatingCopy() {
        val viewModel = NoteViewModel(SavedStateHandle())
        viewModel.loadNotes(appContext, "1234")
        viewModel.addNote(
            title = "Rough plan",
            content = "todo: review budget; schedule vendor call; prepare board update",
            styledContent = RichTextDocument.fromPlainText(
                "todo: review budget; schedule vendor call; prepare board update",
            ),
            images = emptyList(),
            files = emptyList(),
            linkPreviews = emptyList(),
            skipAiSummary = true,
        )
        val source = viewModel.notes.single()

        val rewrittenId = viewModel.rewriteNote(
            noteId = source.id,
            sourceTitle = "Board planning",
            sourceContent = source.content,
            destination = RewriteDestination.CURRENT_NOTE,
        )

        assertEquals(source.id, rewrittenId)
        assertEquals(1, viewModel.notes.size)
        val rewritten = requireNotNull(viewModel.getNoteById(source.id))
        assertEquals("Board planning", rewritten.title)
        assertTrue(rewritten.content.contains("Overview"))
        assertTrue(rewritten.content.contains("• Schedule vendor call."))
        assertTrue(rewritten.styledContent?.spans?.isNotEmpty() == true)
    }

    @Test
    fun rewriteCurrentNotePreservesAttachmentReferences() {
        val viewModel = NoteViewModel(SavedStateHandle())
        viewModel.loadNotes(appContext, "1234")
        val source = Note(
            id = 42L,
            title = "Site visit",
            content = "todo: call alex; send recap\n[[image:0]]",
            styledContent = RichTextDocument.fromPlainText(
                "todo: call alex; send recap\n[[image:0]]",
            ),
            images = listOf(NoteImage(attachmentId = "photo-1")),
        )
        viewModel.restoreNote(source)

        val rewrittenId = viewModel.rewriteNote(
            noteId = source.id,
            sourceContent = source.content,
            destination = RewriteDestination.CURRENT_NOTE,
        )

        assertEquals(source.id, rewrittenId)
        val rewritten = requireNotNull(viewModel.getNoteById(source.id))
        assertEquals(source.images, rewritten.images)
        assertTrue(rewritten.content.contains("[[image:0]]"))
        assertTrue(rewritten.content.contains("Attachments"))
    }
}
