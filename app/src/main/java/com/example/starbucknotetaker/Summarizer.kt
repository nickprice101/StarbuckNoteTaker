package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Simple on-device text summarizer.
 *
 * The implementation ensures T5 encoder/decoder TFLite models are downloaded on-demand
 * and can be extended to run full sequence-to-sequence inference. Currently it falls back to a
 * lightweight extractive summary when the models cannot be used.
 */
class Summarizer(private val context: Context) {
    private var encoder: Interpreter? = null
    private var decoder: Interpreter? = null

    private suspend fun loadModelsIfNeeded() {
        if (encoder != null && decoder != null) return
        try {
            val (encFile, decFile) = ModelFetcher.ensureModels(context)
            encoder = Interpreter(mapFile(encFile))
            decoder = Interpreter(mapFile(decFile))
        } catch (_: Exception) {
            // leave interpreters null to trigger fallback
        }
    }

    private fun mapFile(file: File): MappedByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
    }

    /**
     * Generates a two line summary for the given [text]. Model inference runs on a background
     * dispatcher. If the models cannot be loaded, this falls back to a simple extractive
     * summary using the first couple of sentences.
     */
    suspend fun summarize(text: String): String = withContext(Dispatchers.Default) {
        loadModelsIfNeeded()
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

