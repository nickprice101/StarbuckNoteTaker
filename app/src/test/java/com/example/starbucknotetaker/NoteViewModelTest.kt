package com.example.starbucknotetaker

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
import org.mockito.kotlin.whenever
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
        whenever(summarizer.fallbackSummary(any())).thenReturn("initial summary")
        whenever(summarizer.summarize(any())).thenReturn("mocked summary")

        val viewModel = NoteViewModel()
        setField(viewModel, "summarizer", summarizer)

        viewModel.addNote("Title", "Content", emptyList(), emptyList())

        advanceUntilIdle()

        assertEquals("mocked summary", viewModel.notes[0].summary)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
