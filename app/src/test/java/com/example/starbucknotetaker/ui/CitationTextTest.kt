package com.example.starbucknotetaker.ui

import com.example.starbucknotetaker.MAX_CITATION_LABEL_CHARS
import com.example.starbucknotetaker.richtext.MarkdownRichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationTextTest {
    @Test
    fun `citation pills abbreviate labels to ten characters`() {
        val document = MarkdownRichText.parse(
            "Read [National Aeronautics and Space Administration](https://www.nasa.gov).",
        )

        val citation = buildCitationDisplay(document).citations.single()

        assertEquals("National", citation.label)
        assertTrue(citation.label.length <= MAX_CITATION_LABEL_CHARS)
    }

    @Test
    fun `citation pills preserve already short labels`() {
        val document = MarkdownRichText.parse("Read [NASA](https://www.nasa.gov).")

        assertEquals("NASA", buildCitationDisplay(document).citations.single().label)
    }
}
