package com.example.starbucknotetaker

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

        val engine = LlamaEngine(context)
        try {
            val result = engine.summarise(
                "Team sync notes: finish the Android AI loader fix and open the PR.",
                taskId = "instrumented-real-mlc-smoke",
                maxTokensOverride = 8,
            )

            assertTrue("Expected a non-empty model response", result.isNotBlank())
            assertFalse(
                "Expected real MLC output, not the rule-based fallback",
                result.contains("Team sync notes:", ignoreCase = true),
            )
        } finally {
            engine.close()
        }
    }

    private companion object {
        const val ARG_RUN_REAL_MLC = "runRealMlc"
        const val ARG_ALLOW_SLOW_CPU_MLC = "allowSlowCpuMlc"
    }
}
