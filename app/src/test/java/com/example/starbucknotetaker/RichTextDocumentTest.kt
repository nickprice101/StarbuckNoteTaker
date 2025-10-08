package com.example.starbucknotetaker

import com.example.starbucknotetaker.richtext.RichTextDocument
import com.example.starbucknotetaker.richtext.RichTextStyle
import com.example.starbucknotetaker.richtext.StyleRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextDocumentTest {
    @Test
    fun trimmedPreservesRelativeSpans() {
        val doc = RichTextDocument(
            text = "  Hello World  ",
            spans = listOf(
                StyleRange(2, 7, setOf(RichTextStyle.Bold)),
                StyleRange(8, 11, setOf(RichTextStyle.Italic)),
            )
        )

        val trimmed = doc.trimmed()

        assertEquals("Hello World", trimmed.text)
        assertEquals(2, trimmed.spans.size)
        assertEquals(0, trimmed.spans[0].start)
        assertEquals(5, trimmed.spans[0].end)
        assertEquals(6, trimmed.spans[1].start)
        assertEquals(9, trimmed.spans[1].end)
    }

    @Test
    fun sliceClampsRanges() {
        val doc = RichTextDocument(
            text = "Rich text editor",
            spans = listOf(
                StyleRange(0, 4, setOf(RichTextStyle.Bold)),
                StyleRange(5, 9, setOf(RichTextStyle.Italic)),
            )
        )

        val slice = doc.slice(2, 12)

        assertEquals("ch text ed", slice.text)
        assertEquals(2, slice.spans.size)
        assertEquals(0, slice.spans[0].start)
        assertEquals(2, slice.spans[0].end)
        assertEquals(3, slice.spans[1].start)
        assertEquals(7, slice.spans[1].end)
    }

    @Test
    fun toCharacterStylesExpandsSpans() {
        val doc = RichTextDocument(
            text = "abc",
            spans = listOf(
                StyleRange(0, 2, setOf(RichTextStyle.Bold)),
                StyleRange(1, 3, setOf(RichTextStyle.Italic)),
            )
        )

        val styles = doc.toCharacterStyles()

        assertEquals(3, styles.size)
        assertTrue(styles[0].contains(RichTextStyle.Bold))
        assertTrue(styles[1].containsAll(setOf(RichTextStyle.Bold, RichTextStyle.Italic)))
        assertTrue(styles[2].contains(RichTextStyle.Italic))
    }
}
