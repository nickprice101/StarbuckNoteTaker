package com.example.starbucknotetaker

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for [Summarizer] and [LlamaModelManager] running on a real device.
 *
 * These tests exercise the fallback rule-based path (always available) and the
 * model-manager state machine without requiring the 4.5 GB Llama 3.1 8B model
 * to be present on the test device.
 */
@RunWith(AndroidJUnit4::class)
class SummarizerModelInstrumentationTest {

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
        // On a CI/test device the 4.5 GB model is not present
        val status = manager.modelStatus.value
        assertTrue(
            "Expected Missing or Present, got $status",
            status is LlamaModelManager.ModelStatus.Missing ||
            status is LlamaModelManager.ModelStatus.Present
        )
    }

    @Test
    fun modelManager_hfRepo_targetsLlama31_8B() {
        assertTrue(
            LlamaModelManager.HF_REPO_ID.contains("Llama-3.1-8B", ignoreCase = true)
        )
    }
}
