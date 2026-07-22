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
class MlcAdkModelTest {
    @Test
    fun `adapter maps ADK instruction and turns and streams local tokens`() = runTest {
        val backend = RecordingBackend(result = "Corrected note.")
        val model = MlcAdkModel(backend)
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
        val model = MlcAdkModel(RecordingBackend(result = "ok"))
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
        val model = MlcAdkModel(backend)

        assertEquals(1_000, model.recommendedReformatChunkChars)
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
        assertTrue(fakeModel.requests.single().config.systemInstruction
            ?.parts?.first()?.text.orEmpty().contains("Correct spelling"))
        assertTrue(fakeModel.requests.single().contents.last().parts.first().text.orEmpty()
            .contains("<note>"))
    }

    @Test
    fun `conversation reuses ADK session history across turns`() = runTest {
        val fakeModel = RecordingAdkModel("A concise reply.")
        val conversation = NoteAiAgent.conversation(
            context = ApplicationProvider.getApplicationContext<Context>(),
            sessionId = "session-1",
            noteContext = "Launch on Friday.",
            model = fakeModel,
        )

        conversation.send("When is launch?").toList()
        conversation.send("What day was that?").toList()

        assertEquals(2, fakeModel.requests.size)
        val secondTurnText = fakeModel.requests.last().contents
            .flatMap { it.parts }
            .mapNotNull { it.text }
        assertTrue(secondTurnText.contains("When is launch?"))
        assertTrue(secondTurnText.contains("A concise reply."))
        assertTrue(secondTurnText.contains("What day was that?"))
    }

    @Test
    fun `conversation surfaces a local model failure without hanging`() = runTest {
        val conversation = NoteAiAgent.conversation(
            context = ApplicationProvider.getApplicationContext<Context>(),
            sessionId = "failure-session",
            noteContext = "A local note.",
            model = MlcAdkModel(FailingBackend()),
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
