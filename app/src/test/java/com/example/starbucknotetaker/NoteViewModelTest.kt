package com.example.starbucknotetaker

import androidx.lifecycle.SavedStateHandle
import com.example.starbucknotetaker.richtext.RichTextDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun addNoteUpdatesSummaryFromSummarizer() = runTest(dispatcher.scheduler) {
        val summarizer = mock<Summarizer>()
        whenever(summarizer.quickFallbackSummary(any())).thenReturn("Quick placeholder")
        whenever(summarizer.fallbackSummary(any(), anyOrNull())).thenReturn("Fallback summary")
        whenever(summarizer.summarize(any())).thenReturn("mocked summary")
        whenever(summarizer.consumeDebugTrace()).thenReturn(emptyList())

        val viewModel = NoteViewModel(SavedStateHandle())
        setField(viewModel, "summarizer", summarizer)

        viewModel.addNote(
            "Title",
            "Content",
            RichTextDocument.fromPlainText("Content"),
            emptyList(),
            emptyList(),
            emptyList(),
        )

        assertEquals("Quick placeholder", viewModel.notes[0].summary)
        verify(summarizer).quickFallbackSummary(any())

        advanceUntilIdle()

        assertEquals("mocked summary", viewModel.notes[0].summary)
        verify(summarizer).fallbackSummary(any(), anyOrNull())
    }

    @Test
    fun pendingUnlockNavigationNoteIdPersistsAcrossSavedState() = runTest(dispatcher.scheduler) {
        val handle = SavedStateHandle()
        val viewModel = NoteViewModel(handle)

        viewModel.setPendingUnlockNavigationNoteId(123L)
        assertEquals(123L, viewModel.pendingUnlockNavigationNoteId.value)

        val restoredHandle = SavedStateHandle(handle.keys().associateWith { key -> handle.get<Any?>(key) })
        val restoredViewModel = NoteViewModel(restoredHandle)

        assertEquals(123L, restoredViewModel.pendingUnlockNavigationNoteId.value)

        restoredViewModel.clearPendingUnlockNavigationNoteId()
        assertEquals(null, restoredViewModel.pendingUnlockNavigationNoteId.value)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
