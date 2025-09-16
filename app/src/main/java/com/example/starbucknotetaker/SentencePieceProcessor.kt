package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import ai.djl.sentencepiece.SpTokenizer
import ai.djl.util.Platform
import ai.djl.util.Utils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrapper around DJL's SentencePiece tokenizer providing simple encode/decode
 * helpers used by the T5 summarization model.
 */
class SentencePieceProcessor(
    private val nativeInstaller: (Context) -> Unit = { SentencePieceNativeInstaller.ensureInstalled(it) }
) {
    private lateinit var tokenizer: SpTokenizer

    /** Loads the SentencePiece model from [modelPath]. */
    fun load(context: Context, modelPath: String) {
        nativeInstaller(context)
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

private object SentencePieceNativeInstaller {
    private const val TAG = "SentencePieceInstaller"
    private val installed = AtomicBoolean(false)

    fun ensureInstalled(context: Context) {
        if (installed.get()) return
        synchronized(this) {
            if (installed.get()) return
            val destination = resolveDestinationFile() ?: run {
                Log.w(TAG, "Unable to determine DJL cache directory for SentencePiece native library")
                return
            }
            if (destination.exists() && destination.length() > 0) {
                installed.set(true)
                return
            }
            val source = findSourceLibrary(context) ?: run {
                Log.w(TAG, "Could not locate packaged SentencePiece native library to install")
                return
            }
            val parent = destination.parentFile
            if (parent == null) {
                Log.w(TAG, "SentencePiece destination has no parent directory: ${destination.absolutePath}")
                return
            }
            if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
                Log.w(TAG, "Failed to create directory for SentencePiece native library at ${parent.absolutePath}")
                return
            }
            try {
                source.inputStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
                destination.setReadable(true, false)
                destination.setExecutable(true, false)
                destination.setWritable(true, true)
                installed.set(true)
                Log.d(TAG, "Installed SentencePiece native library for DJL at ${destination.absolutePath}")
            } catch (io: IOException) {
                Log.w(TAG, "Failed copying SentencePiece native library to DJL cache", io)
                destination.delete()
            } catch (t: Throwable) {
                Log.w(TAG, "Unexpected failure while installing SentencePiece native library", t)
                destination.delete()
            }
        }
    }

    private fun resolveDestinationFile(): File? =
        try {
            val cacheDir = Utils.getEngineCacheDir("sentencepiece")
            val platform = Platform.detectPlatform("sentencepiece")
            val version = platform.version
            cacheDir.resolve(version).resolve(System.mapLibraryName("sentencepiece_native")).toFile()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving DJL cache path for SentencePiece", t)
            null
        }

    private fun findSourceLibrary(context: Context): File? {
        val candidates = listOf("penguin", "djl_tokenizer", "sentencepiece_native")
        for (name in candidates) {
            val file = NativeLibraryLoader.findLibraryOnDisk(context, name)
            if (file != null && file.exists() && file.length() > 0) {
                return file
            }
        }
        return null
    }
}

