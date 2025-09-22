package com.example.starbucknotetaker

import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

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
        whenever(summarizer.fallbackSummary(any(), anyOrNull())).thenReturn(NoteNatureType.GENERAL_NOTE.humanReadable)
        whenever(summarizer.summarize(any())).thenReturn("mocked summary")
        whenever(summarizer.consumeDebugTrace()).thenReturn(emptyList())

        val viewModel = NoteViewModel(SavedStateHandle())
        setField(viewModel, "summarizer", summarizer)

        viewModel.addNote("Title", "Content", emptyList(), emptyList(), emptyList())

        assertEquals(NoteNatureType.GENERAL_NOTE.humanReadable, viewModel.notes[0].summary)

        advanceUntilIdle()

        assertEquals("mocked summary", viewModel.notes[0].summary)
    }

    @Test
    fun noteIdToOpenAfterUnlockRoundTripInSavedState() {
        val key = getStaticStringField("NOTE_ID_TO_OPEN_AFTER_UNLOCK_KEY")
        val savedState = SavedStateHandle()
        val viewModel = NoteViewModel(savedState)

        assertNull(viewModel.noteIdToOpenAfterUnlock.value)
        assertNull(savedState.get<Long?>(key))

        viewModel.setNoteIdToOpenAfterUnlock(72L)

        assertEquals(72L, viewModel.noteIdToOpenAfterUnlock.value)
        assertEquals(72L, savedState.get<Long?>(key))

        val restored = NoteViewModel(SavedStateHandle(mapOf(key to 72L)))

        assertEquals(72L, restored.noteIdToOpenAfterUnlock.value)

        restored.clearNoteIdToOpenAfterUnlock()
        assertNull(restored.noteIdToOpenAfterUnlock.value)

        viewModel.clearNoteIdToOpenAfterUnlock()
        assertNull(viewModel.noteIdToOpenAfterUnlock.value)
        assertNull(savedState.get<Long?>(key))
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getStaticStringField(name: String): String {
        val field = NoteViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null) as String
    }
}
