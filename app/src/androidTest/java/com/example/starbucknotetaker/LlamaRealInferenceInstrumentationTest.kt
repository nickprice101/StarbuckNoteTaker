package com.example.starbucknotetaker

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LlamaRealInferenceInstrumentationTest {

    @Test
    fun llamaEngine_generatesChatResponseWithDownloadedLiteRtModel() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_RUN_REAL_LITERT) == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelManager = LlamaModelManager(context)
        if (!modelManager.isModelPresent()) {
            val observedPercentages = mutableListOf<Int>()
            val progressCollector = launch {
                modelManager.modelStatus.collect { status ->
                    if (status is LlamaModelManager.ModelStatus.Downloading) {
                        observedPercentages += status.progressPercent
                    }
                }
            }
            yield()
            val downloaded = try {
                modelManager.downloadModel()
            } finally {
                progressCollector.cancelAndJoin()
            }
            assertTrue("Expected LiteRT-LM model download to complete", downloaded)
            assertTrue(
                "Expected byte-linked download progress between 1 and 99 percent",
                observedPercentages.any { it in 1..99 },
            )
            assertTrue(
                "Expected model download percentage never to move backwards: $observedPercentages",
                observedPercentages.zipWithNext().all { (before, after) -> after >= before },
            )
        }

        val engine = LlamaEngineProvider.acquire(context)
        try {
            val warmStartedAt = SystemClock.elapsedRealtime()
            assertTrue("Expected shared LiteRT-LM engine to warm successfully", engine.warmUp())
            val warmupMs = SystemClock.elapsedRealtime() - warmStartedAt

            val generationStartedAt = SystemClock.elapsedRealtime()
            val updates = NoteAiAgent.conversation(
                context = context,
                sessionId = "instrumented-real-litert-smoke",
                noteContext = "A tiny note used to verify private on-device chat.",
            ).send("What is two plus two? Reply with only the number.").toList()
            val result = updates
                .filterIsInstance<AgentTurnUpdate.Complete>()
                .lastOrNull()
                ?.text
                .orEmpty()
            val generationMs = SystemClock.elapsedRealtime() - generationStartedAt
            Log.i(TAG, "ADK LiteRT-LM benchmark warmupMs=$warmupMs generationMs=$generationMs result=$result")

            assertTrue("Expected a non-empty model response", result.isNotBlank())
            assertFalse(
                "Expected hidden Qwen reasoning markers to be removed",
                result.contains("<think>", ignoreCase = true),
            )
            assertTrue(
                "Expected a warm simple chat response in under 30 seconds; took ${generationMs}ms",
                generationMs < 30_000L,
            )
        } finally {
            LlamaEngineProvider.closeNow()
        }
    }

    private companion object {
        const val TAG = "LlamaRealInferenceTest"
        const val ARG_RUN_REAL_LITERT = "runRealLiteRt"
    }
}
