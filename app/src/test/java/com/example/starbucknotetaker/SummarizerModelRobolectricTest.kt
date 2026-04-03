package com.example.starbucknotetaker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.MappedByteBuffer

@RunWith(RobolectricTestRunner::class)
class SummarizerModelRobolectricTest {

    private lateinit var appContext: Application
    private lateinit var loadedModelBuffer: MappedByteBuffer
    private lateinit var interpreter: RecordingInterpreter

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun summarizerFeedsInterpreterWithInt32SequenceInputAndGeneratesEnhancedSummary() = runTest {
        val mappingJson = appContext.assets.open("category_mapping.json").bufferedReader().use { it.readText() }
        val categories = JSONObject(mappingJson).getJSONArray("categories")
            .let { array -> List(array.length()) { array.getString(it) } }
        val expectedCategory = requireNotNull(categories.firstOrNull { it == "FOOD_RECIPE" }) {
            "category_mapping.json does not contain FOOD_RECIPE"
        }

        val predictedIndex = categories.indexOf(expectedCategory)
        require(predictedIndex >= 0) { "Expected category index must be present" }

        val summarizer = Summarizer(
            context = appContext,
            interpreterFactory = { buffer ->
                loadedModelBuffer = buffer
                interpreter = RecordingInterpreter(
                    predictedIndex = predictedIndex,
                    score = 0.92f,
                    baselineScore = 0.01f,
                    categories = categories,
                )
                interpreter
            },
            assetLoader = { _, name ->
                when (name) {
                    Summarizer.TOKENIZER_VOCAB_ASSET_NAME ->
                        "[PAD]\n[UNK]\nhomemade\npasta\nrecipe\nmix\ncups\nflour\nwith\neggs\nuntil\ndough\nforms\nknead\nfor\nminutes\nsmooth\nand\nelastic\nroll\nthin\nmachine\ncut\ninto\nfettuccine\nstrips\nboil\nin\nsalted\nwater".byteInputStream()
                    else -> appContext.assets.open(name)
                }
            },
            logger = { message, throwable -> throw AssertionError("Summarizer error: $message", throwable) },
            debugSink = { }
        )

        val noteText = "Homemade pasta recipe: mix 2 cups flour with 3 eggs until dough forms, knead for 10 minutes until smooth " +
            "and elastic, roll thin with pasta machine, cut into fettuccine strips, boil in salted water for 3 minutes."

        val summary = summarizer.summarize(noteText)
        val predictedCategory = interpreter.lastPredictedCategory

        assertNotNull("Summarizer should load the TensorFlow Lite model", loadedModelBuffer)
        assertTrue(
            "Loaded model buffer should expose data",
            loadedModelBuffer.capacity() > 0
        )
        assertTrue("Interpreter should receive [1,120] int32 input", interpreter.receivedIntSequenceInput)
        assertEquals(expectedCategory, predictedCategory)
        assertTrue("Enhanced summary should mention pasta", summary.contains("pasta", ignoreCase = true))
    }

    @Test
    fun summarizerNormalizesTitlePrefixedSourceBeforeInference() = runTest {
        val mappingJson = appContext.assets.open("category_mapping.json").bufferedReader().use { it.readText() }
        val categories = JSONObject(mappingJson).getJSONArray("categories")
            .let { array -> List(array.length()) { array.getString(it) } }
        val predictedIndex = categories.indexOf(categories.first())

        val summarizer = Summarizer(
            context = appContext,
            interpreterFactory = { buffer ->
                loadedModelBuffer = buffer
                interpreter = RecordingInterpreter(
                    predictedIndex = predictedIndex,
                    score = 0.92f,
                    baselineScore = 0.01f,
                    categories = categories,
                )
                interpreter
            },
            assetLoader = { _, name ->
                when (name) {
                    Summarizer.TOKENIZER_VOCAB_ASSET_NAME ->
                        "[PAD]\n[UNK]\ntravel\nprep\nbook\nhotel\nand\ncheck\ntrain\nschedule".byteInputStream()
                    else -> appContext.assets.open(name)
                }
            },
            logger = { message, throwable -> throw AssertionError("Summarizer error: $message", throwable) },
            debugSink = { }
        )

        summarizer.summarize("Title: Travel prep\n\nBook hotel and check train schedule")

        assertEquals(2, interpreter.receivedInputTokenIds.getOrNull(0))
        assertEquals(3, interpreter.receivedInputTokenIds.getOrNull(1))
    }


    @Test
    fun warmUpRefreshesCachedModelAssetBeforeCreatingInterpreter() = runTest {
        val modelsDir = java.io.File(appContext.filesDir, Summarizer.MODELS_DIR_NAME).apply { mkdirs() }
        val cachedModel = java.io.File(modelsDir, Summarizer.MODEL_ASSET_NAME)
        cachedModel.writeBytes(byteArrayOf(7, 7, 7, 7))

        val expectedModelBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        val mappingJson = """{"categories":["REMINDER"]}"""
        val vocab = "[PAD]\n[UNK]\nreminder"

        var observedBytes = byteArrayOf()
        val summarizer = Summarizer(
            context = appContext,
            interpreterFactory = { mappedBuffer ->
                val duplicate = mappedBuffer.duplicate()
                val bytes = ByteArray(duplicate.remaining())
                duplicate.get(bytes)
                observedBytes = bytes
                RecordingInterpreter(
                    predictedIndex = 0,
                    score = 0.95f,
                    baselineScore = 0.01f,
                    categories = listOf("REMINDER"),
                )
            },
            assetLoader = { _, name ->
                when (name) {
                    Summarizer.MODEL_ASSET_NAME -> expectedModelBytes.inputStream()
                    Summarizer.CATEGORY_MAPPING_ASSET_NAME -> mappingJson.byteInputStream()
                    Summarizer.TOKENIZER_VOCAB_ASSET_NAME -> vocab.byteInputStream()
                    else -> error("Unexpected asset requested: $name")
                }
            },
            logger = { message, throwable -> throw AssertionError("Summarizer error: $message", throwable) },
            debugSink = { }
        )

        summarizer.warmUp()

        assertTrue("Cached model should be overwritten with the bundled asset", cachedModel.readBytes().contentEquals(expectedModelBytes))
        assertTrue("Interpreter should receive refreshed model bytes", observedBytes.contentEquals(expectedModelBytes))
    }

    @Test
    fun summarizerRetriesInferenceWithStringInputWhenModelRejectsIntTokens() = runTest {
        val summarizer = Summarizer(
            context = appContext,
            interpreterFactory = {
                RecordingInterpreter(
                    predictedIndex = 0,
                    score = 0.93f,
                    baselineScore = 0.01f,
                    categories = listOf("REMINDER"),
                    rejectIntInput = true,
                ).also { interpreter = it }
            },
            assetLoader = { _, name ->
                when (name) {
                    Summarizer.MODEL_ASSET_NAME -> byteArrayOf(1, 2, 3).inputStream()
                    Summarizer.CATEGORY_MAPPING_ASSET_NAME -> """{"categories":["REMINDER"]}""".byteInputStream()
                    Summarizer.TOKENIZER_VOCAB_ASSET_NAME -> "[PAD]\n[UNK]\nreminder".byteInputStream()
                    else -> error("Unexpected asset requested: $name")
                }
            },
            logger = { message, throwable -> throw AssertionError("Summarizer error: $message", throwable) },
            debugSink = { }
        )

        val summary = summarizer.summarize("Reminder: call dentist tomorrow")

        assertTrue("Interpreter should receive fallback string input", interpreter.receivedStringInput)
        assertFalse("Int sequence should be rejected first for this model", interpreter.receivedIntSequenceInput)
        assertTrue("Summary should still be produced after retry", summary.contains("Reminder", ignoreCase = true))
    }

    private class RecordingInterpreter(
        private val predictedIndex: Int,
        private val score: Float,
        private val baselineScore: Float,
        private val categories: List<String>,
        private val rejectIntInput: Boolean = false,
    ) : LiteInterpreter {

        var receivedIntSequenceInput: Boolean = false
            private set
        var receivedInputTokenIds: IntArray = intArrayOf()
            private set
        var receivedStringInput: Boolean = false
            private set
        var lastPredictedCategory: String? = null
        override val inputTensorCount: Int
            get() = 1

        override fun getOutputTensor(index: Int): LiteTensor {
            throw UnsupportedOperationException("Not used in test")
        }

        override fun getInputTensor(index: Int): LiteTensor {
            throw UnsupportedOperationException("Not used in test")
        }

        override fun run(input: Any, output: Any) {
            if (rejectIntInput && input is Array<*> && input.firstOrNull() is IntArray) {
                throw IllegalArgumentException(
                    "Cannot convert between a TensorFlowLite tensor with type STRING and a Java object of type [[I (which is compatible with the TensorFlowLite type INT32)."
                )
            }
            val ids = (input as? Array<IntArray>)?.getOrNull(0)
            receivedIntSequenceInput = ids?.size == 120
            receivedInputTokenIds = ids ?: intArrayOf()
            val stringInput = (input as? Array<String>)?.getOrNull(0)
            receivedStringInput = !stringInput.isNullOrBlank()
            val scores = output as Array<FloatArray>
            for (i in scores[0].indices) {
                scores[0][i] = if (i == predictedIndex) score else baselineScore
            }
            lastPredictedCategory = categories.getOrNull(predictedIndex)
        }

        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {
            throw UnsupportedOperationException("Not used in test")
        }

        override fun close() {}
    }
}
