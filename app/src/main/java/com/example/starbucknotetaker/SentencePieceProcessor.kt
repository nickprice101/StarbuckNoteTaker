package com.example.starbucknotetaker

import java.io.File

/**
 * Minimal stand-in for the real SentencePiece tokenizer.
 * This implementation uses a trivial whitespace-based scheme so that
 * summarization can run without the actual native dependency.
 * It does not generate meaningful summaries but keeps the pipeline functional.
 */
class SentencePieceProcessor {
    fun load(model: String) {
        // In a real implementation, [model] would be parsed. Here we only check existence.
        File(model)
    }

    fun encodeAsIds(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf()
        return text.split(" ").map { it.hashCode() and 0x7FFFFFFF % VOCAB_SIZE }.toIntArray()
    }

    fun decodeIds(ids: IntArray): String {
        return ids.joinToString(" ") { it.toString() }
    }

    companion object {
        private const val VOCAB_SIZE = 32128
    }
}
