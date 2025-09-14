package com.example.starbucknotetaker

import ai.djl.sentencepiece.SpTokenizer
import java.io.File

/**
 * Wrapper around DJL's SentencePiece tokenizer providing simple encode/decode
 * helpers used by the T5 summarization model.
 */
class SentencePieceProcessor {
    private lateinit var tokenizer: SpTokenizer

    /** Loads the SentencePiece model from [modelPath]. */
    fun load(modelPath: String) {
        File(modelPath).inputStream().use { inputStream ->
            tokenizer = SpTokenizer(inputStream)
        }
    }

    /** Encodes [text] into an array of token IDs. */
    fun encodeAsIds(text: String): IntArray = tokenizer.processor.encode(text)

    /** Decodes token [ids] back into a string. */
    fun decodeIds(ids: IntArray): String = tokenizer.processor.decode(ids)

    /** Releases native resources. */
    fun close() {
        tokenizer.close()
    }
}

