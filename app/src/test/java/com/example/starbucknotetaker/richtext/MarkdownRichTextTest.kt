package com.example.starbucknotetaker.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRichTextTest {
    @Test
    fun convertsHeadingsEmphasisAndListsToEditableRichText() {
        val document = MarkdownRichText.parse(
            """
            ## Project update

            **Status:** The release is *ready*.
            - Send the client recap
            - [x] Confirm the launch date
            """.trimIndent(),
        )

        assertFalse(document.text.contains("##"))
        assertFalse(document.text.contains("**"))
        assertEquals(
            "Project update\n\nStatus: The release is ready.\n" +
                "• Send the client recap\n☑ Confirm the launch date",
            document.text,
        )
        val characterStyles = document.toCharacterStyles()
        assertTrue(RichTextStyle.Bold in characterStyles[0])
        assertTrue(RichTextStyle.Bold in characterStyles[document.text.indexOf("Status")])
        assertTrue(RichTextStyle.Italic in characterStyles[document.text.indexOf("ready")])
    }

    @Test
    fun keepsLinksAndAttachmentMarkersUsable() {
        val document = MarkdownRichText.parse(
            "Read [the brief](https://example.com/brief).\n\n[[image:0]]",
        )

        assertEquals(
            "Read the brief.\n\n[[image:0]]",
            document.text,
        )
        val citationIndex = document.text.indexOf("the brief")
        val citation = document.toCharacterStyles()[citationIndex]
            .filterIsInstance<RichTextStyle.Citation>()
            .single()
        assertEquals("https://example.com/brief", citation.url)
    }
}
