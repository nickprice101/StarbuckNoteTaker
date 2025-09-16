package com.example.starbucknotetaker

import android.content.Context
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import java.io.File
import java.io.FileInputStream

/**
 * Wrapper around DJL's HuggingFace tokenizer providing encode/decode helpers
 * used by the T5 summarization model.
 */
class SentencePieceProcessor(
    private val tokenizerFactory: (Context, File) -> HuggingFaceTokenizer = { _, file ->
        FileInputStream(file).use { stream ->
            HuggingFaceTokenizer.newInstance(stream, emptyMap())
        }
    }
) {
    private var tokenizer: HuggingFaceTokenizer? = null

    /** Loads the tokenizer model from [modelPath]. */
    fun load(context: Context, modelPath: String) {
        close()
        val file = File(modelPath)
        if (!file.exists()) {
            throw IllegalArgumentException("Tokenizer model missing at $modelPath")
        }
        tokenizer = tokenizerFactory(context, file)
    }

    /** Encodes [text] into an array of token IDs. */
    fun encodeAsIds(text: String): IntArray {
        val encoding = tokenizer?.encode(text)
            ?: throw IllegalStateException("Tokenizer has not been loaded")
        return encoding.ids.map { it.toInt() }.toIntArray()
    }

    /** Decodes token [ids] back into a string. */
    fun decodeIds(ids: IntArray): String {
        val tok = tokenizer ?: throw IllegalStateException("Tokenizer has not been loaded")
        val longs = LongArray(ids.size) { idx -> ids[idx].toLong() }
        return tok.decode(longs, false)
    }

    /** Releases native resources. */
    fun close() {
        tokenizer?.close()
        tokenizer = null
    }
}
