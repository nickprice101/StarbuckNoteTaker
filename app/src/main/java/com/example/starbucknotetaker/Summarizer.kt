package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/**
 * Simple on-device text summarizer.
 *
 * The implementation attempts to load T5 encoder/decoder TFLite models from assets and
 * can be extended to run full sequence-to-sequence inference. Currently it falls back to a
 * lightweight extractive summary when the models cannot be used.
 */
class Summarizer(private val context: Context) {
    private val encoder: Interpreter? = loadModel("encoder_int8_dynamic.tflite")
    private val decoder: Interpreter? = loadModel("decoder_step_int8_dynamic.tflite")

    private fun loadModel(name: String): Interpreter? {
        return try {
            val fileDescriptor = context.assets.openFd(name)
            FileInputStream(fileDescriptor.fileDescriptor).use { fis ->
                val mapped: MappedByteBuffer =
                    fis.channel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
                Interpreter(mapped)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generates a two line summary for the given [text]. Model inference runs on a background
     * dispatcher. If the bundled models are unavailable, this falls back to a simple extractive
     * summary using the first couple of sentences.
     */
    suspend fun summarize(text: String): String = withContext(Dispatchers.Default) {
        if (encoder == null || decoder == null) {
            return@withContext fallbackSummary(text)
        }
        // TODO: Implement full encoder/decoder inference using the loaded models and tokenizer.
        // For now we return a basic extractive summary.
        fallbackSummary(text)
    }

    fun fallbackSummary(text: String): String {
        val sentences = text.split('.', '!', '?')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val candidate = sentences.take(2).joinToString(". ")
        return if (candidate.isNotEmpty()) candidate else text.take(200)
    }
}
