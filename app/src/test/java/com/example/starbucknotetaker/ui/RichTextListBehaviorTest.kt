package com.example.starbucknotetaker.ui

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextListBehaviorTest {

    @Test
    fun bulletEnterInsertsNewItemPrefix() {
        val initialText = "• First"
        val state = RichTextState(plainValue(initialText, initialText.length))

        val handled = state.handleEnterKey()

        assertTrue(handled)
        assertEquals("• First\n• ", state.value.text)
        assertEquals(state.value.text.length, state.value.selection.start)
        assertEquals(state.value.selection.start, state.value.selection.end)
        assertEquals(state.value.text.length, state.value.characterStyles.size)
    }

    @Test
    fun bulletEnterOnEmptyItemExitsList() {
        val state = RichTextState(plainValue("• ", 2))

        val handled = state.handleEnterKey()

        assertTrue(handled)
        assertEquals("\n", state.value.text)
        assertEquals(1, state.value.selection.start)
        assertEquals(state.value.selection.start, state.value.selection.end)
        assertEquals(state.value.text.length, state.value.characterStyles.size)
    }

    @Test
    fun numberedEnterAddsIncrementedItem() {
        val initialText = "1. Item"
        val state = RichTextState(plainValue(initialText, initialText.length))

        val handled = state.handleEnterKey()

        assertTrue(handled)
        assertEquals("1. Item\n2. ", state.value.text)
        assertEquals(state.value.text.length, state.value.selection.start)
        assertEquals(state.value.selection.start, state.value.selection.end)
    }

    @Test
    fun bulletEnterSplitsParagraph() {
        val initialText = "• Hello world"
        val caret = "• Hello ".length
        val state = RichTextState(plainValue(initialText, caret))

        val handled = state.handleEnterKey()

        assertTrue(handled)
        assertEquals("• Hello \n• world", state.value.text)
    }

    @Test
    fun applyBulletFormattingInsertsPrefixAndMovesCaret() {
        val state = RichTextState(RichTextValue.empty())

        state.applyFormattingAction(FormattingAction.BulletList)

        assertEquals("• ", state.value.text)
        assertEquals(TextRange(2), state.value.selection)
    }

    @Test
    fun applyNumberedFormattingStartsAtOne() {
        val baseText = "Example"
        val state = RichTextState(plainValue(baseText, baseText.length))

        state.applyFormattingAction(FormattingAction.NumberedList)

        assertEquals("1. Example", state.value.text)
        assertEquals(TextRange(baseText.length + 3), state.value.selection)
    }

    private fun plainValue(text: String, selection: Int): RichTextValue {
        return RichTextValue(
            text = text,
            selection = TextRange(selection),
            characterStyles = List(text.length) { emptySet() },
        )
    }
}
