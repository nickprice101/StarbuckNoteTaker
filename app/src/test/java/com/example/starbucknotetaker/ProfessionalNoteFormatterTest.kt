package com.example.starbucknotetaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfessionalNoteFormatterTest {
    @Test
    fun detectsCommonRewriteRequests() {
        assertTrue(
            ProfessionalNoteFormatter.isRewriteRequest(
                "rewrite and format this note in a more professional way",
            ),
        )
        assertTrue(ProfessionalNoteFormatter.isRewriteRequest("clean this note up"))
        assertFalse(ProfessionalNoteFormatter.isRewriteRequest("what is the capital of Iran?"))
    }

    @Test
    fun formatsMessyNoteIntoConciseProfessionalSections() {
        val formatted = ProfessionalNoteFormatter.format(
            "client meeting notes: call alex tomorrow, update launch doc, send recap to team",
        )

        assertTrue(formatted.contains("Overview"))
        assertTrue(formatted.contains("Action Items"))
        assertTrue(formatted.contains("- Update launch doc."))
        assertTrue(formatted.contains("- Send recap to team."))
        assertFalse(formatted.contains(", update launch doc,"))
    }
}
