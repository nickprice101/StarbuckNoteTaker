package com.example.starbucknotetaker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    fun summarizerFeedsInterpreterWith2dStringInputAndGeneratesEnhancedSummary() = runTest {
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
                    categories = categories,
                )
                interpreter
            },
            assetLoader = { ctx, name -> ctx.assets.open(name) },
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
        assertTrue("Interpreter should receive a nested [batch, 1] string input", interpreter.received2dStringInput)
        assertEquals(expectedCategory, predictedCategory)
        assertTrue("Enhanced summary should mention pasta", summary.contains("pasta", ignoreCase = true))
    }

    private class RecordingInterpreter(
        private val predictedIndex: Int,
        private val score: Float,
        private val categories: List<String>,
    ) : LiteInterpreter {

        var received2dStringInput: Boolean = false
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
            @Suppress("UNCHECKED_CAST")
            val strings = input as? Array<Array<String>>
            received2dStringInput = strings?.size == 1 && strings[0].size == 1
            val scores = output as Array<FloatArray>
            for (i in scores[0].indices) {
                scores[0][i] = if (i == predictedIndex) score else 0.01f
            }
            lastPredictedCategory = categories.getOrNull(predictedIndex)
        }

        override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {
            throw UnsupportedOperationException("Not used in test")
        }

        override fun close() {}
    }
}
