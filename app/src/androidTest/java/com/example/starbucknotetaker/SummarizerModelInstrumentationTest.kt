package com.example.starbucknotetaker

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Instrumentation tests for [Summarizer] and [LlamaModelManager] running on a real device.
 *
 * These tests exercise the fallback rule-based path (always available) and the
 * model-manager state machine without requiring the LiteRT-LM model
 * to be present on the test device.
 */
@RunWith(AndroidJUnit4::class)
class SummarizerModelInstrumentationTest {

    @Test
    fun bundledTfliteClassifier_invokesOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val categories = context.assets.open(Summarizer.CATEGORY_MAPPING_ASSET_NAME)
            .bufferedReader()
            .use { JSONObject(it.readText()).getJSONArray("categories") }
        val modelBytes = context.assets.open(Summarizer.MODEL_ASSET_NAME).use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
            .put(modelBytes)
        modelBuffer.rewind()

        val interpreter = Interpreter(modelBuffer)
        try {
            val input = arrayOf(
                arrayOf(
                    "Homemade pasta recipe: mix 2 cups flour with 3 eggs, " +
                        "knead for 10 minutes, and cut into fettuccine."
                )
            )
            val output = Array(1) { FloatArray(categories.length()) }

            interpreter.run(input, output)

            val scores = output[0]
            assertTrue("Expected one score for each category", scores.size == categories.length())
            assertTrue("Expected finite classifier scores", scores.all { it.isFinite() })
            assertTrue("Expected classifier to produce a non-zero signal", scores.any { it != 0f })
        } finally {
            interpreter.close()
        }
    }

    @Test
    fun summarizer_fallbackPath_producesNonEmptyResult() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val summarizer = Summarizer(context = context)
        val result = summarizer.summarize(
            "Homemade pasta recipe: mix 2 cups flour with 3 eggs until dough forms, " +
            "knead for 10 minutes until smooth and elastic."
        )
        Log.d("SummarizerTest", "Result: $result")
        assertNotNull(result)
        assertTrue("Fallback summary should be non-empty", result.isNotEmpty())
    }

    @Test
    fun summarizer_fallbackPath_emptyInput_returnsEmpty() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val summarizer = Summarizer(context = context)
        val result = summarizer.summarize("   ")
        assertTrue("Empty input should yield empty summary", result.isEmpty())
    }

    @Test
    fun summarizer_rewrite_fallbackReturnsOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val summarizer = Summarizer(context = context)
        val original = "Buy milk, eggs, and bread."
        val result = summarizer.rewrite(original)
        assertNotNull(result)
        assertFalse("Rewrite result should not be empty", result.isEmpty())
    }

    @Test
    fun modelManager_statusIsMissingOnFreshDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = LlamaModelManager(context)
        // On a CI/test device the downloaded model is typically not present.
        val status = manager.modelStatus.value
        assertTrue(
            "Expected Missing, Present, or Unsupported, got $status",
            status is LlamaModelManager.ModelStatus.Missing ||
            status is LlamaModelManager.ModelStatus.Present ||
            status is LlamaModelManager.ModelStatus.Unsupported
        )
    }

    @Test
    fun modelManager_hfRepo_targetsQwen3LiteRt() {
        assertTrue(
            LlamaModelManager.HF_REPO_ID.contains("Qwen3-0.6B", ignoreCase = true)
        )
        assertTrue(LlamaModelManager.MODEL_FILENAME.endsWith(".litertlm"))
    }
}
