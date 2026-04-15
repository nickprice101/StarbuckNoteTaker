package com.example.starbucknotetaker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [LlamaEngine] and [Summarizer] when running without the MLC LLM
 * native library (i.e. in the Robolectric JVM environment where native .so files
 * are not loaded).
 *
 * All tests exercise the rule-based fallback path, prompt-construction helpers,
 * and the public API surface that callers depend on.  They do not require the
 * ~4.5 GB Llama 3.1 8B model to be present on disk.
 */
@RunWith(RobolectricTestRunner::class)
class LlamaEngineUnitTest {

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    // ------------------------------------------------------------------
    // Fallback-path tests via Summarizer (no native library)
    // ------------------------------------------------------------------

    @Test
    fun summarizer_fallbackProducesNonEmptyResult_forPlainText() = runTest {
        val summarizer = Summarizer(appContext)
        val result = summarizer.summarize("This is a simple note about grocery shopping.")
        assertNotNull(result)
        // In fallback mode the result is a rule-based preview — non-empty for non-empty input.
        // We cannot assert the exact content since it is model-dependent when the LLM is present.
        assertTrue("Expected non-empty fallback summary", result.isNotEmpty())
    }

    @Test
    fun summarizer_fallbackReturnsEmpty_forBlankInput() = runTest {
        val summarizer = Summarizer(appContext)
        val result = summarizer.summarize("   ")
        assertEquals("", result)
    }

    @Test
    fun summarizer_rewrite_fallbackReturnsOriginalText() = runTest {
        val summarizer = Summarizer(appContext)
        val original = "Buy milk and eggs."
        val result = summarizer.rewrite(original)
        // Fallback for rewrite returns the original trimmed text.
        assertEquals(original.trim(), result)
    }

    @Test
    fun summarizer_answer_fallbackContainsDownloadHint() = runTest {
        val summarizer = Summarizer(appContext)
        val result = summarizer.answer("What is the capital of France?")
        assertTrue(
            "Expected download-hint message in fallback answer",
            result.contains("Settings", ignoreCase = true) || result.contains("download", ignoreCase = true),
        )
    }

    @Test
    fun summarizer_quickFallbackSummary_isSynchronousAndNonEmpty() {
        val summarizer = Summarizer(appContext)
        val result = summarizer.quickFallbackSummary("Note content about work meetings.")
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun summarizer_stateStartsReady() {
        val summarizer = Summarizer(appContext)
        assertEquals(Summarizer.SummarizerState.Ready, summarizer.state.value)
    }

    // ------------------------------------------------------------------
    // Static helper tests (these always run rule-based, no LLM needed)
    // ------------------------------------------------------------------

    @Test
    fun lightweightPreview_returnsAtMostTwoSentences() {
        val text = "First sentence. Second sentence. Third sentence."
        val result = Summarizer.lightweightPreview(text)
        // Should contain at most the first two sentences
        assertFalse(result.contains("Third sentence"))
    }

    @Test
    fun lightweightPreview_handlesEmptyInput() {
        assertEquals("", Summarizer.lightweightPreview(""))
        assertEquals("", Summarizer.lightweightPreview("   "))
    }

    @Test
    fun smartTruncate_respectsMaxLength() {
        val long = "a".repeat(200)
        val result = Summarizer.smartTruncate(long, 100)
        assertTrue("Expected ≤100 chars, got ${result.length}", result.length <= 100)
    }

    @Test
    fun smartTruncate_doesNotTruncateShortText() {
        val short = "Hello world."
        assertEquals(short, Summarizer.smartTruncate(short, 140))
    }

    @Test
    fun normalizeForModelInput_stripsTitlePrefix() {
        val input = "Title: My Note\n\nNote body text."
        val result = Summarizer.normalizeForModelInput(input)
        assertFalse(result.contains("Title:"))
        assertTrue(result.contains("My Note"))
    }

    @Test
    fun normalizeForModelInput_collapseWhitespace() {
        val input = "Multiple   spaces   here."
        val result = Summarizer.normalizeForModelInput(input)
        assertFalse(result.contains("  "))
    }

    @Test
    fun tokenizeForModelInput_outputIsFixedLength() {
        val vocab = TokenizerVocabulary.from(listOf("[UNK]", "hello", "world"))
        val tokens = Summarizer.tokenizeForModelInput("hello world", vocab)
        assertEquals(120, tokens.size)
    }

    // ------------------------------------------------------------------
    // LlamaModelManager — unit tests (no network, no file-system changes)
    // ------------------------------------------------------------------

    @Test
    fun modelManager_initialStatusIsMissing_whenModelDirIsEmpty() {
        val manager = LlamaModelManager(appContext)
        val status = manager.modelStatus.value
        // On a fresh Robolectric context the model directory does not exist.
        assertTrue(
            "Expected Missing status in test environment, got $status",
            status is LlamaModelManager.ModelStatus.Missing,
        )
    }

    @Test
    fun modelManager_getModelPath_returnsNull_whenMissing() {
        val manager = LlamaModelManager(appContext)
        val path = manager.getModelPath()
        assertTrue("Expected null path when model not present", path == null)
    }

    @Test
    fun modelManager_isModelPresent_returnsFalse_whenMissing() {
        val manager = LlamaModelManager(appContext)
        assertFalse(manager.isModelPresent())
    }

    @Test
    fun modelManager_modelSizeLabel_isCorrect() {
        assertEquals("~4.5 GB", LlamaModelManager.MODEL_SIZE_LABEL)
    }

    @Test
    fun modelManager_hfRepoId_targetsLlama31_8B() {
        assertTrue(
            "HuggingFace repo should reference Llama-3.1-8B",
            LlamaModelManager.HF_REPO_ID.contains("Llama-3.1-8B", ignoreCase = true),
        )
    }

    @Test
    fun modelManager_modelLibName_targetsLlama31_8B() {
        assertTrue(
            "Model lib name should reference Llama-3.1-8B",
            LlamaModelManager.MODEL_LIB_NAME.contains("Llama-3.1-8B", ignoreCase = true),
        )
    }
}
