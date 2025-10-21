package com.example.starbucknotetaker

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SummarizerModelInstrumentationTest {
    @Test
    fun summarizerClassifiesSampleNoteAndGeneratesEnhancedSummary() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val assetDir = File(targetContext.cacheDir, "summarizer-model").apply {
            if (!exists()) {
                mkdirs()
            }
        }

        val assetManager = targetContext.assets
        val assetNames = listOf("note_classifier.tflite", "category_mapping.json")
        assetNames.forEach { name ->
            assetManager.open(name).use { input ->
                val target = File(assetDir, name)
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        }

        // Ensure the TensorFlow Lite runtime and flex delegate libraries are available.
        System.loadLibrary("tensorflowlite_jni")
        System.loadLibrary("tensorflowlite_flex_jni")
        runCatching { System.loadLibrary("tensorflowlite_select_tf_ops") }

        val summarizer = Summarizer(
            context = targetContext,
            interpreterFactory = { buffer -> TfLiteInterpreter.create(buffer) },
            assetLoader = { _, assetName -> File(assetDir, assetName).inputStream() },
            logger = { message, throwable -> Log.e("SummarizerTest", message, throwable) },
            debugSink = { message -> Log.d("SummarizerTest", message) },
        )

        val noteText = "Homemade pasta recipe: mix 2 cups flour with 3 eggs until dough forms, knead for 10 minutes until smooth " +
            "and elastic, roll thin with pasta machine, cut into fettuccine strips, boil in salted water for 3 minutes."
        val summary = summarizer.summarize(noteText)
        Log.i("SummarizerTest", "Enhanced summary example:\n$summary")

        val trace = summarizer.consumeDebugTrace()
        val categoryLine = trace.firstOrNull { it.startsWith("predicted category=") }
            ?: error("Expected predicted category in debug trace: $trace")
        val predictedCategory = categoryLine.substringAfter("predicted category=").substringBefore(' ')

        assertEquals("FODO_RECIPE", predictedCategory)
        assertTrue("Enhanced summary should not be blank", summary.isNotBlank())
        assertTrue("Enhanced summary should mention pasta", summary.contains("pasta", ignoreCase = true))
    }
}
