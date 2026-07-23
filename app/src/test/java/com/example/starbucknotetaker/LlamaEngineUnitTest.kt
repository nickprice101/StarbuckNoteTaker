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
import java.io.File
import java.io.RandomAccessFile

/**
 * Unit tests for [LlamaEngine] and [Summarizer] when running without the
 * LiteRT-LM Android native runtime.
 *
 * All tests exercise the rule-based fallback path, prompt-construction helpers,
 * and the public API surface that callers depend on.  They do not require the
 * downloaded model to be present on disk.
 */
@RunWith(RobolectricTestRunner::class)
class LlamaEngineUnitTest {

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext.applicationInfo.nativeLibraryDir = "/data/app/example/lib/x86_64"
        File(appContext.filesDir, LlamaModelManager.MODEL_SUBDIR).deleteRecursively()
        File(appContext.filesDir, LlamaModelManager.MODEL_SUBDIR_X86_64).deleteRecursively()
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
    fun summarizer_answer_fallbackContainsActionableAvailabilityMessage() = runTest {
        val summarizer = Summarizer(appContext)
        val result = summarizer.answer("What is the capital of France?")
        assertTrue("Expected non-empty fallback answer", result.isNotBlank())
        assertTrue(
            "Expected model availability message in fallback answer",
            result.contains("model", ignoreCase = true) &&
                (
                    result.contains("Settings", ignoreCase = true) ||
                        result.contains("download", ignoreCase = true) ||
                        result.contains("device", ignoreCase = true) ||
                        result.contains("RAM", ignoreCase = true)
                    ),
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

    // ------------------------------------------------------------------
    // LlamaModelManager — unit tests (no network, no file-system changes)
    // ------------------------------------------------------------------

    @Test
    fun modelManager_initialStatusIsNotPresent_whenModelDirIsEmptyOrAbiUnsupported() {
        val manager = LlamaModelManager(appContext)
        val status = manager.modelStatus.value
        // On a fresh Robolectric context the model directory does not exist. Some host
        // Test environments may also report an ABI not packaged by LiteRT-LM.
        assertTrue(
            "Expected Missing or Unsupported status in test environment, got $status",
            status is LlamaModelManager.ModelStatus.Missing ||
                status is LlamaModelManager.ModelStatus.Unsupported,
        )
    }

    @Test
    fun modelManager_getModelPath_returnsNull_whenMissing() {
        val manager = LlamaModelManager(appContext)
        val path = manager.getModelPath()
        assertTrue("Expected null path when model not present", path == null)
    }

    @Test
    fun modelManager_getModelPath_returnsNull_whenBundleIsPartial() {
        val dir = File(appContext.filesDir, LlamaModelManager.MODEL_SUBDIR).also { it.mkdirs() }
        File(dir, LlamaModelManager.MODEL_FILENAME).writeText("partial")
        File(dir, ".model.sha256").writeText(LlamaModelManager.MODEL_SHA256)

        val manager = LlamaModelManager(appContext)

        assertTrue("Expected null path for a partial bundle", manager.getModelPath() == null)
    }

    @Test
    fun modelManager_getModelPath_returnsPath_whenBundleAndChecksumArePresent() {
        val dir = File(appContext.filesDir, LlamaModelManager.MODEL_SUBDIR).also { it.mkdirs() }
        val bundle = File(dir, LlamaModelManager.MODEL_FILENAME)
        RandomAccessFile(bundle, "rw").use { it.setLength(LlamaModelManager.MODEL_SIZE_BYTES) }
        File(dir, ".model.sha256").writeText(LlamaModelManager.MODEL_SHA256)

        val manager = LlamaModelManager(appContext)

        assertEquals(bundle.absolutePath, manager.getModelPath())
    }

    @Test
    fun modelManager_isModelPresent_returnsFalse_whenMissing() {
        val manager = LlamaModelManager(appContext)
        assertFalse(manager.isModelPresent())
    }

    @Test
    fun modelManager_modelSizeLabel_isCorrect() {
        assertEquals("~475 MB", LlamaModelManager.MODEL_SIZE_LABEL)
    }

    @Test
    fun modelManager_hfRepoId_targetsQwen3LiteRtBundle() {
        assertTrue(
            LlamaModelManager.HF_REPO_ID.contains("Qwen3-0.6B", ignoreCase = true),
        )
        assertTrue(LlamaModelManager.MODEL_FILENAME.endsWith(".litertlm"))
    }

    @Test
    fun modelManager_debugInfo_reportsLiteRtModel() {
        val manager = LlamaModelManager(appContext)
        val debugInfo = manager.debugModelDirInfo()

        assertTrue(debugInfo.contains("LiteRT-LM"))
        assertTrue(debugInfo.contains(LlamaModelManager.MODEL_FILENAME))
    }

    @Test
    fun modelManager_downloadUrl_isPinnedAndChecksummed() {
        assertTrue(LlamaModelManager.MODEL_URL.contains(LlamaModelManager.HF_REVISION))
        assertEquals(64, LlamaModelManager.MODEL_SHA256.length)
    }

    @Test
    fun modelManager_downloadPercentage_tracksDownloadedBytes() {
        val total = 1_000L

        assertEquals(0, LlamaModelManager.downloadProgressPercent(0L, total))
        assertEquals(25, LlamaModelManager.downloadProgressPercent(250L, total))
        assertEquals(50, LlamaModelManager.downloadProgressPercent(500L, total))
        assertEquals(100, LlamaModelManager.downloadProgressPercent(1_500L, total))
    }

    @Test
    fun modelManager_supportedModelAbis_includeX8664Emulator() {
        assertTrue(LlamaModelManager.SUPPORTED_MODEL_ABIS.contains("arm64-v8a"))
        assertTrue(LlamaModelManager.SUPPORTED_MODEL_ABIS.contains("x86_64"))
    }

    @Test
    fun modelManager_selectRuntimeAbi_prefersInstalledNativeLibraryDir() {
        val abi = LlamaModelManager.selectRuntimeAbi(
            nativeLibraryDir = "/data/app/example/lib/x86_64",
            supportedAbis = listOf("arm64-v8a", "x86_64"),
        )

        assertEquals("x86_64", abi)
    }

    @Test
    fun modelManager_selectRuntimeAbi_mapsArm64RuntimeDirToApkAbi() {
        val abi = LlamaModelManager.selectRuntimeAbi(
            nativeLibraryDir = "/data/app/example/lib/arm64",
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals("arm64-v8a", abi)
    }

    @Test
    fun modelManager_selectRuntimeAbi_fallsBackToSupportedAbiList() {
        val abi = LlamaModelManager.selectRuntimeAbi(
            nativeLibraryDir = null,
            supportedAbis = listOf("arm64-v8a", "x86_64"),
        )

        assertEquals("arm64-v8a", abi)
    }

}
