package com.example.starbucknotetaker

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QwenAiSupportTest {
    @Test
    fun tokenBudgetCountsNaturalLanguageAndNonAsciiText() {
        val short = QwenTokenBudget.estimateTokens("Book dentist appointment Friday.")
        val long = QwenTokenBudget.estimateTokens(
            "Book dentist appointment Friday and remember the insurance card, address, and time.",
        )
        val nonAscii = QwenTokenBudget.estimateTokens("東京で珈琲を飲む")

        assertTrue(short > 0)
        assertTrue(long > short)
        assertTrue(nonAscii > 0)
    }

    @Test
    fun localRetrieverSelectsRelevantRelatedNoteAndExcludesUnreadableNote() {
        val current = Note(id = 1, title = "Launch", content = "Prepare release checklist.")
        val relevant = Note(
            id = 2,
            title = "Vendor decision",
            content = "Priya selected Northwind as the launch analytics vendor.",
        )
        val unrelated = Note(id = 3, title = "Groceries", content = "Milk, eggs, coffee.")
        val locked = Note(
            id = 4,
            title = "Private budget",
            content = "Northwind budget is 4500 dollars.",
            isLocked = true,
        )

        val context = LocalNoteContextRetriever.retrieve(
            question = "Which analytics vendor did Priya select?",
            currentNote = current,
            notes = listOf(current, unrelated, locked, relevant),
            canRead = { !it.isLocked },
            maxChars = 1_800,
        )

        assertTrue(context.contains("Northwind"))
        assertTrue(context.contains("Priya"))
        assertFalse(context.contains("4500"))
        assertTrue(context.contains("role=\"current\""))
    }

    @Test
    fun researchPlanParsesQwenJsonAndBoundsQueries() {
        val plan = QwenResearchPlan.parse(
            """
            ```json
            {"needs_web":true,"queries":["official Android release","Android security bulletin",
            "ignored third query"],"freshness":"current","source_types":["official","primary"]}
            ```
            """.trimIndent(),
            question = "What is the current Android release?",
        )

        assertTrue(plan.needsWeb)
        assertEquals(2, plan.queries.size)
        assertEquals("current", plan.freshness)
        assertEquals(listOf("official", "primary"), plan.sourceTypes)
    }

    @Test
    fun summaryProtocolRendersCategoryAwareTwoFieldPreview() {
        val rendered = QwenSummaryProtocol.render(
            """
            {"type":"MEETING_RECAP","gist":"Mobile staging flow agreed",
            "action":"Riley will monitor oven temperatures","deadline":"","key_detail":"Reorder grande lids"}
            """.trimIndent(),
        )

        assertTrue(rendered.contains("Mobile staging flow agreed"))
        assertTrue(rendered.contains("Riley will monitor oven temperatures"))
        assertFalse(rendered.contains("\"type\""))
    }

    @Test
    fun groundingValidatorFindsNewAndMissingProtectedFacts() {
        val source =
            "Pay €120 on Friday at https://example.com and run `git status` for Dr. Rivera."
        val output =
            "Pay €180 on Friday and run `git diff` for Dr. Rivera."

        val unsupported = AiGroundingValidator.unsupportedFacts(source, output)
        val missing = AiGroundingValidator.missingFacts(source, output)

        assertTrue(unsupported.any { it.contains("180") })
        assertTrue(unsupported.contains("`git diff`"))
        assertTrue(missing.any { it.contains("120") })
        assertTrue(missing.any { it.contains("https://example.com") })
    }

    @Test
    fun structuredDocumentLabelsProtectedMarkdownElements() {
        val described = StructuredNoteDocument.describe(
            """
            # Plan
            - Check [docs](https://example.com)
            [[file:0]]
            ```
            git status
            ```
            """.trimIndent(),
        )

        assertTrue(described.contains("kind=\"heading\""))
        assertTrue(described.contains("kind=\"linked_text\""))
        assertTrue(described.contains("kind=\"attachment\""))
        assertTrue(described.contains("kind=\"code_fence\""))
    }

    @Test
    fun reformatResearchPolicyRequiresAnExplicitOnlineMode() {
        assertFalse(ReformatResearchPolicy.requiresOnlineEvidence("Reformat this note professionally"))
        assertTrue(ReformatResearchPolicy.requiresOnlineEvidence("Verify the citations in this note"))
        assertTrue(ReformatResearchPolicy.requiresOnlineEvidence("Apply the APA style guide"))
    }

    @Test
    fun qwenChunkerRespectsEstimatedTokenBudgetAndKeepsOutlineEndpoints() {
        val source = (1..80).joinToString("\n\n") { index ->
            if (index == 1) "# Opening" else "Paragraph $index contains a concrete project detail."
        } + "\n\n# Closing\nFinal decision is Friday."

        val chunks = NoteTextChunker.chunkForQwen(source, maxChars = 900, maxTokens = 180)
        val outline = NoteTextChunker.outlineSource(source, maxChars = 700)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { QwenTokenBudget.estimateTokens(it) <= 220 })
        assertTrue(outline.contains("# Opening"))
        assertTrue(outline.contains("# Closing"))
        assertTrue(outline.length <= 700)
    }

    @Test
    fun conversationMemoryPersistsOnlyWhenRequested() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val first = ConversationMemoryStore(context)
        first.put(42L, "- User: prefers concise answers", persistent = true)
        first.put(43L, "- Note: temporary secret", persistent = false)

        val second = ConversationMemoryStore(context)

        assertTrue(second.get(42L, persistent = true).contains("concise"))
        assertTrue(second.get(43L, persistent = false).contains("temporary"))
    }

    @Test
    fun structuredPromptIncludesBoundedConversationMemory() {
        val prompt = AgentContextPromptBuilder.build(
            currentNote = "Launch is Friday.",
            userRequest = "When is launch?",
            recentConversation = "User: Is launch confirmed?\nAssistant: Yes.",
            conversationMemory = "- Note: launch decision is final",
            maxChars = 900,
        )

        assertTrue(prompt.contains("<conversation_memory>"))
        assertTrue(prompt.contains("launch decision is final"))
        assertTrue(prompt.contains("<user_request>\nWhen is launch?"))
        assertTrue(prompt.length <= 900)
    }

    @Test
    fun summaryCacheIsContentAddressed() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val cache = QwenSummaryCache(context)
        cache.put("Title: Alpha\n\nDecision is Friday.", "Alpha decision is Friday.")

        assertEquals(
            "Alpha decision is Friday.",
            QwenSummaryCache(context).get("Title: Alpha\n\nDecision is Friday."),
        )
        assertTrue(cache.get("Title: Alpha\n\nDecision is Monday.").isNullOrBlank())
    }
}
