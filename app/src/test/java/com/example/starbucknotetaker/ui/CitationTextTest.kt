package com.example.starbucknotetaker.ui

import androidx.compose.ui.graphics.Color
import com.example.starbucknotetaker.richtext.MarkdownRichText
import org.junit.Assert.assertEquals
import org.junit.Test

class CitationTextTest {
    @Test
    fun `citation pills use website name instead of article label`() {
        val document = MarkdownRichText.parse(
            "Read [Geothermal energy - Wikipedia](https://en.wikipedia.org/wiki/Geothermal_energy).",
        )

        val citation = buildCitationDisplay(document).citations.single()

        assertEquals("Wikipedia", citation.label)
    }

    @Test
    fun `citation pills preserve already short labels`() {
        val document = MarkdownRichText.parse("Read [NASA](https://www.nasa.gov).")

        assertEquals("NASA", buildCitationDisplay(document).citations.single().label)
    }

    @Test
    fun `citation pills use recognizable website colors`() {
        assertEquals(
            Color(0xFF202122),
            citationSiteStyle("https://en.wikipedia.org/wiki/Notes").background,
        )
        assertEquals(
            Color(0xFFFF0000),
            citationSiteStyle("https://youtube.com/watch?v=123").background,
        )
    }

    @Test
    fun `citation pills request the favicon from the linked website`() {
        assertEquals(
            "https://science.nasa.gov/favicon.ico",
            faviconUrl("https://science.nasa.gov/mission/webb/"),
        )
        assertEquals(null, faviconUrl("not a link"))
    }
}
