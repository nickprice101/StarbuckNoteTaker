package com.example.starbucknotetaker

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LlamaRealInferenceInstrumentationTest {

    @Test
    fun llamaEngine_generatesSummaryWithDownloadedMlcModel() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_RUN_REAL_MLC) == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelManager = LlamaModelManager(context)
        assumeTrue(
            modelManager.getRuntimeMlcDeviceType() != "cpu" ||
                arguments.getString(ARG_ALLOW_SLOW_CPU_MLC) == "true"
        )
        if (!modelManager.isModelPresent()) {
            val downloaded = modelManager.downloadModel()
            assertTrue("Expected MLC model download to complete", downloaded)
        }

        val engine = LlamaEngineProvider.acquire(context)
        try {
            val warmStartedAt = SystemClock.elapsedRealtime()
            assertTrue("Expected shared MLC engine to warm successfully", engine.warmUp())
            val warmupMs = SystemClock.elapsedRealtime() - warmStartedAt

            val generationStartedAt = SystemClock.elapsedRealtime()
            val result = engine.summarise(
                "Team sync notes: finish the Android AI loader fix and open the PR.",
                taskId = "instrumented-real-mlc-smoke",
                maxTokensOverride = 8,
            )
            val generationMs = SystemClock.elapsedRealtime() - generationStartedAt
            Log.i(TAG, "Real 3B benchmark warmupMs=$warmupMs generationMs=$generationMs result=$result")

            assertTrue("Expected a non-empty model response", result.isNotBlank())
            assertFalse(
                "Expected real MLC output, not the rule-based fallback",
                result.contains("Team sync notes:", ignoreCase = true),
            )
            assertTrue(
                "Expected a warm 8-token response in under 30 seconds; took ${generationMs}ms",
                generationMs < 30_000L,
            )
        } finally {
            LlamaEngineProvider.closeNow()
        }
    }

    private companion object {
        const val TAG = "LlamaRealInferenceTest"
        const val ARG_RUN_REAL_MLC = "runRealMlc"
        const val ARG_ALLOW_SLOW_CPU_MLC = "allowSlowCpuMlc"
    }
}
