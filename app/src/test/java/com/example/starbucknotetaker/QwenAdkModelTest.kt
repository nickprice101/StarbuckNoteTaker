package com.example.starbucknotetaker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QwenAdkModelTest {
    @Test
    fun `editable agent prompts are packaged as a build asset`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val prompts = AiAgentPrompts.load(context)

        assertTrue(prompts.summariser.startsWith("Role: You are the on-device note summariser"))
        assertTrue(prompts.summariser.contains("exactly one JSON object"))
        assertTrue(prompts.summariser.contains("category-aware enhanced summary"))
        assertTrue(prompts.chatbot.startsWith("Role: You are a knowledge and writing assistant"))
        assertTrue(prompts.chatbot.contains("existing note is optional context"))
        assertTrue(prompts.chatbot.contains("Never refuse because the answer is absent from the note"))
        assertTrue(prompts.chatbot.contains("never tell the user how to perform research"))
        assertTrue(prompts.chatbot.contains("distinct web_research block"))
        assertTrue(prompts.chatbot.contains("request does not contain the /note tag"))
        assertTrue(prompts.chatbot.contains("never return links or a list of links without a summary"))
        assertTrue(prompts.reformatting.startsWith("Role: You are a document formatting assistant"))
        assertTrue(prompts.reformatting.contains("Treat all attached images, files, and linked content"))
    }

    @Test
    fun `adapter maps ADK instruction and turns and streams local tokens`() = runTest {
        val backend = RecordingBackend(result = "Corrected note.")
        val model = QwenAdkModel(backend)
        val request = LlmRequest(
            contents = listOf(
                Content(role = Role.USER, parts = listOf(Part(text = "messy note"))),
                Content(role = Role.MODEL, parts = listOf(Part(text = "previous reply"))),
                Content(role = Role.USER, parts = listOf(Part(text = "make it clearer"))),
            ),
            config = GenerateContentConfig(
                systemInstruction = Content(parts = listOf(Part(text = "Correct grammar."))),
                maxOutputTokens = 200,
                temperature = 0.1f,
            ),
        )

        val responses = model.generateContent(request, stream = true).toList()

        assertEquals(listOf("system", "user", "assistant", "user"), backend.messages.map { it.role })
        assertEquals("Correct grammar.", backend.messages.first().content)
        assertEquals(200, backend.maxTokens)
        assertTrue(responses.any { it.partial })
        assertEquals("Corrected note.", responses.last().content?.parts?.first()?.text)
        assertFalse(responses.last().partial)
    }

    @Test
    fun `prompt budget preserves instruction and newest user turn`() {
        val model = QwenAdkModel(RecordingBackend(result = "ok"))
        val request = LlmRequest(
            contents = listOf(
                Content(role = Role.USER, parts = listOf(Part(text = "old ".repeat(300)))),
                Content(role = Role.MODEL, parts = listOf(Part(text = "older answer"))),
                Content(role = Role.USER, parts = listOf(Part(text = "newest question"))),
            ),
            config = GenerateContentConfig(
                systemInstruction = Content(parts = listOf(Part(text = "Be helpful"))),
            ),
        )

        val messages = with(model) { request.toLocalMessages(limit = 800) }

        assertEquals("system", messages.first().role)
        assertEquals("Be helpful", messages.first().content)
        assertEquals("newest question", messages.last().content)
        assertTrue(messages.sumOf { it.content.length } <= 800)
    }

    @Test
    fun `reformat chunk recommendation fits compact model prompt`() {
        val backend = RecordingBackend(result = "ok", promptCharLimit = 2_200)
        val model = QwenAdkModel(backend)

        assertEquals(800, model.recommendedReformatChunkChars)
    }

    @Test
    fun `reformat workflow is executed through an ADK agent`() = runTest {
        val fakeModel = RecordingAdkModel(
            "## Meeting\n\n- Call Alex tomorrow.\n- Send the corrected recap to the team.",
        )

        val result = NoteAiAgent.reformat(
            context = ApplicationProvider.getApplicationContext<Context>(),
            noteText = "meeting notes call alex tomorow and send the corected recap to the team",
            model = fakeModel,
        )

        assertTrue(result.contains("## Meeting"))
        assertTrue(result.contains("tomorrow"))
        assertEquals(2, fakeModel.requests.size)
        assertTrue(fakeModel.requests.last().config.systemInstruction
            ?.parts?.first()?.text.orEmpty().contains("Correct spelling"))
        assertTrue(fakeModel.requests.last().contents.last().parts.first().text.orEmpty()
            .contains("<structured_note_fragment>"))
    }

    @Test
    fun `conversation sends current note and recent turns as explicit context`() = runTest {
        val fakeModel = RecordingAdkModel("A concise reply.")
        val conversation = NoteAiAgent.conversation(
            context = ApplicationProvider.getApplicationContext<Context>(),
            sessionId = "session-1",
            noteContext = "Launch on Friday.",
            model = fakeModel,
        )

        conversation.send("/note When is launch?").toList()
        conversation.send("/note What day was that?").toList()

        assertEquals(2, fakeModel.requests.size)
        val secondTurnText = fakeModel.requests.last().contents
            .flatMap { it.parts }
            .mapNotNull { it.text }
            .joinToString("\n")
        assertTrue(secondTurnText.contains("/note When is launch?"))
        assertTrue(secondTurnText.contains("A concise reply."))
        assertTrue(secondTurnText.contains("/note What day was that?"))
        assertTrue(secondTurnText.contains("<local_note role=\"current\""))
        assertTrue(secondTurnText.contains("Launch on Friday."))
        assertTrue(secondTurnText.contains("<current_note usage=\"source\">"))
        assertTrue(secondTurnText.contains("<recent_conversation>"))
        assertEquals(
            AiAgentPrompts.load(ApplicationProvider.getApplicationContext()).chatbot,
            fakeModel.requests.last().config.systemInstruction?.parts?.first()?.text,
        )
    }

    @Test
    fun `structured context keeps note history and current request within budget`() {
        val note = "Opening fact. " + "middle detail ".repeat(200) + "Latest decision is Friday."

        val prompt = AgentContextPromptBuilder.build(
            currentNote = note,
            recentConversation = "User: Who owns it?\nAssistant: Priya owns it.",
            userRequest = "When is the latest decision due?",
            maxChars = 1_100,
        )

        assertTrue(prompt.length <= 1_100)
        assertTrue(prompt.contains("Opening fact."))
        assertTrue(prompt.contains("Latest decision is Friday."))
        assertTrue(prompt.contains("Priya owns it."))
        assertTrue(prompt.contains("<user_request>\nWhen is the latest decision due?\n</user_request>"))
    }

    @Test
    fun `structured context keeps web research separate from the note`() {
        val prompt = AgentContextPromptBuilder.build(
            currentNote = "Unrelated shopping list.",
            webResearch = "Source 1: Energy Department\nGeothermal power uses underground heat.",
            userRequest = "How does geothermal power work?",
            maxChars = 1_100,
        )

        assertTrue(prompt.contains(
            "<current_note usage=\"context_only\">\nUnrelated shopping list.\n</current_note>",
        ))
        assertTrue(prompt.contains(
            "<web_research>\nSource 1: Energy Department",
        ))
        assertTrue(prompt.contains("Geothermal power uses underground heat."))
        assertTrue(prompt.contains("</web_research>"))
    }

    @Test
    fun `reformat deduplicator removes repeated prose but preserves code`() {
        val formatted = ReformattedNoteDeduplicator.removeRepeatedContent(
            """
            ## Launch
            Launch is Friday.
            - Launch is Friday.

            ```text
            repeat-me
            repeat-me
            ```
            """.trimIndent(),
        )

        assertEquals(1, Regex("Launch is Friday\\.").findAll(formatted).count())
        assertEquals(2, Regex("repeat-me").findAll(formatted).count())
    }

    @Test
    fun `conversation surfaces a local model failure without hanging`() = runTest {
        val conversation = NoteAiAgent.conversation(
            context = ApplicationProvider.getApplicationContext<Context>(),
            sessionId = "failure-session",
            noteContext = "A local note.",
            model = QwenAdkModel(FailingBackend()),
        )

        try {
            withTimeout(2_000) { conversation.send("Help me").toList() }
            fail("Expected the local model failure to reach the conversation")
        } catch (failure: IllegalStateException) {
            val failureText = generateSequence<Throwable>(failure) { it.cause }
                .joinToString(" | ") { it.message.orEmpty() }
            assertTrue(failureText, failureText.contains("model is missing", ignoreCase = true))
        }
    }

    @Test
    fun `offline stable fact still uses the local model`() = runTest {
        val fakeModel = RecordingAdkModel("J. R. R. Tolkien wrote The Hobbit.")
        val conversation = NoteConversationAgent(
            model = fakeModel,
            sessionId = "offline-fact",
            noteContext = "",
            systemInstruction = "Answer accurately.",
            webResearcher = WebResearcher { WebLookupResult.offline(it) },
        )

        val updates = conversation.send("Who wrote The Hobbit?").toList()

        assertEquals(1, fakeModel.requests.size)
        assertEquals(
            "J. R. R. Tolkien wrote The Hobbit.",
            (updates.last() as AgentTurnUpdate.Complete).text,
        )
    }

    @Test
    fun `current fact alerts when internet research is unavailable`() = runTest {
        val fakeModel = RecordingAdkModel("This must not be used.")
        val conversation = NoteConversationAgent(
            model = fakeModel,
            sessionId = "offline-current",
            noteContext = "",
            systemInstruction = "Answer accurately.",
            webResearcher = WebResearcher { WebLookupResult.offline(it) },
        )

        val updates = conversation.send("What is the latest Android version?").toList()

        assertTrue(fakeModel.requests.isEmpty())
        assertEquals(
            AssistantWebLookup.INTERNET_REQUIRED_MESSAGE,
            (updates.last() as AgentTurnUpdate.Complete).text,
        )
    }

    @Test
    fun `researched answer always appends abbreviated markdown source links`() = runTest {
        val fakeModel = RecordingAdkModel("The Artemis II mission is the next crewed test flight.")
        val research = WebLookupResult(
            query = "latest Artemis mission",
            results = listOf(
                WebLookupEntry(
                    title = "NASA",
                    url = "https://www.nasa.gov/mission/artemis-ii/",
                    snippet = "NASA describes Artemis II as a crewed test flight.",
                ),
            ),
        )
        val conversation = NoteConversationAgent(
            model = fakeModel,
            sessionId = "online-current",
            noteContext = "Space notes",
            systemInstruction = "Answer accurately.",
            webResearcher = WebResearcher { research },
        )

        val updates = conversation.send("What is the latest Artemis mission?").toList()
        val answer = (updates.last() as AgentTurnUpdate.Complete).text

        assertTrue(answer.contains("[NASA](https://www.nasa.gov/mission/artemis-ii/)"))
        assertTrue(fakeModel.requests.single().contents.last().parts.first().text.orEmpty()
            .contains("On-device web research"))
    }

    @Test
    fun `citation-only researched draft is replaced with extracted findings`() = runTest {
        val fakeModel = RecordingAdkModel(
            "See [Example](https://example.com/brussels-steak).",
        )
        val research = WebLookupResult(
            query = "Brussels steak restaurant",
            results = listOf(
                WebLookupEntry(
                    title = "Restaurant review",
                    url = "https://example.com/brussels-steak",
                    snippet = "Example Grill serves dry-aged steak in central Brussels and accepts reservations.",
                ),
            ),
        )
        val conversation = NoteConversationAgent(
            model = fakeModel,
            sessionId = "research-link-only",
            noteContext = "Trip to Brussels",
            systemInstruction = "Answer accurately.",
            webResearcher = WebResearcher { research },
        )

        val answer = (
            conversation.send("Recommend me a steak restaurant").toList().last()
                as AgentTurnUpdate.Complete
            ).text

        assertTrue(answer.contains("serves dry-aged steak"))
        assertTrue(answer.contains("accepts reservations"))
        assertTrue(answer.contains("[Example](https://example.com/brussels-steak)"))
    }

    @Test
    fun `note tag uses only the current note and does not trigger ordinary fact lookup`() = runTest {
        val researchedQueries = mutableListOf<String>()
        val fakeModel = RecordingAdkModel("Chez Example serves steak.")
        val conversation = NoteConversationAgent(
            model = fakeModel,
            sessionId = "note-source",
            noteContext = "Chez Example serves steak. Cafe Sample serves pasta.",
            systemInstruction = "Answer accurately.",
            webResearcher = WebResearcher { query ->
                researchedQueries += query
                WebLookupResult.offline(query)
            },
        )

        val answer = (
            conversation.send("Based on /note, which restaurants serve steak?").toList().last()
                as AgentTurnUpdate.Complete
            ).text
        val prompt = fakeModel.requests.single().contents.last().parts.first().text.orEmpty()

        assertEquals("Chez Example serves steak.", answer)
        assertTrue(researchedQueries.isEmpty())
        assertTrue(prompt.contains("<current_note usage=\"source\">"))
        assertTrue(prompt.contains("Chez Example serves steak."))
    }

    @Test
    fun `researched evidence replaces a model refusal caused by missing note context`() = runTest {
        val fakeModel = RecordingAdkModel(
            "The information provided in the note is not relevant to answering the question.",
        )
        val research = WebLookupResult(
            query = "Explain geothermal power",
            results = listOf(
                WebLookupEntry(
                    title = "Energy Department",
                    url = "https://www.energy.gov/geothermal",
                    snippet = "Geothermal plants use heat from beneath Earth's surface to generate electricity.",
                ),
            ),
        )
        val conversation = NoteConversationAgent(
            model = fakeModel,
            sessionId = "research-refusal",
            noteContext = "Unrelated shopping list",
            systemInstruction = "Answer accurately.",
            webResearcher = WebResearcher { research },
        )

        val updates = conversation.send("Explain geothermal power").toList()
        val answer = (updates.last() as AgentTurnUpdate.Complete).text

        assertTrue(answer.contains("Geothermal plants use heat"))
        assertTrue(answer.contains("[Energy](https://www.energy.gov/geothermal)"))
        assertFalse(answer.contains("does not provide enough context"))
        assertFalse(answer.contains("not relevant to answering the question"))
    }

    @Test
    fun `bare web research follow-up answers the original request`() = runTest {
        val researchedQueries = mutableListOf<String>()
        val fakeModel = RecordingAdkModel("A direct answer.")
        val conversation = NoteConversationAgent(
            model = fakeModel,
            sessionId = "research-follow-up",
            noteContext = "Unrelated note",
            systemInstruction = "Answer directly.",
            webResearcher = WebResearcher { query ->
                researchedQueries += query
                WebLookupResult(
                    query = query,
                    results = listOf(
                        WebLookupEntry(
                            title = "Space Weather Article",
                            url = "https://en.wikipedia.org/wiki/Carrington_Event",
                            snippet = "The Carrington Event was a powerful geomagnetic storm in 1859.",
                        ),
                    ),
                )
            },
        )

        conversation.send("Help me understand the Carrington Event").toList()
        val updates = conversation.send("Conduct your own web research").toList()

        assertEquals(listOf("Help me understand the Carrington Event"), researchedQueries)
        assertEquals(
            AssistantWebLookup.RESEARCH_PROGRESS_MESSAGE,
            (updates.first() as AgentTurnUpdate.Partial).text,
        )
        val researchedPrompt = fakeModel.requests.last().contents.last().parts.first().text.orEmpty()
        assertTrue(researchedPrompt.contains(
            "<user_request>\nHelp me understand the Carrington Event\n</user_request>",
        ))
        assertTrue((updates.last() as AgentTurnUpdate.Complete).text.contains(
            "[Wikipedia](https://en.wikipedia.org/wiki/Carrington_Event)",
        ))
    }

    @Test
    fun `chunker preserves long note content and bounds every fragment`() {
        val source = (1..20).joinToString("\n\n") { index ->
            "Paragraph $index contains a decision and a follow-up action for the project team."
        }

        val chunks = NoteTextChunker.chunk(source, maxChars = 400)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 400 })
        assertEquals(
            source.replace(Regex("\\n{2,}"), " ").replace(Regex("\\s+"), " ").trim(),
            chunks.joinToString(" ").replace(Regex("\\s+"), " ").trim(),
        )
    }

    private class RecordingBackend(
        private val result: String,
        override val promptCharLimit: Int = 4_000,
    ) : LocalChatBackend {
        override val maxOutputTokens: Int = 512
        var messages: List<LlamaEngine.ChatMessage> = emptyList()
        var maxTokens: Int = 0

        override suspend fun complete(
            messages: List<LlamaEngine.ChatMessage>,
            taskId: String,
            maxTokens: Int,
            temperature: Float,
            topP: Float,
            onToken: (String) -> Unit,
        ): String {
            this.messages = messages
            this.maxTokens = maxTokens
            onToken("Corrected ")
            onToken("note.")
            return result
        }
    }

    private class RecordingAdkModel(
        private val response: String,
    ) : Model {
        override val name: String = "fake-local-model"
        val requests = mutableListOf<LlmRequest>()

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
            requests += request
            return flowOf(
                LlmResponse(
                    content = Content(role = Role.MODEL, parts = listOf(Part(text = response))),
                ),
            )
        }
    }

    private class FailingBackend : LocalChatBackend {
        override val promptCharLimit: Int = 4_000
        override val maxOutputTokens: Int = 512

        override fun unavailableReason(): String = "The model is missing"

        override suspend fun complete(
            messages: List<LlamaEngine.ChatMessage>,
            taskId: String,
            maxTokens: Int,
            temperature: Float,
            topP: Float,
            onToken: (String) -> Unit,
        ): String = throw IllegalStateException("The model is missing")
    }
}
