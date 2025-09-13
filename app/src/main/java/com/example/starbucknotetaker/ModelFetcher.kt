package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Downloads the summarization models from GitHub Releases on first run.
 * The models are stored under `files/models` in the app's internal storage.
 */
object ModelFetcher {
    private const val BASE_URL = "https://github.com/nickprice101/StarbuckNoteTaker/releases/download/v1.0.0/"

    private const val ENCODER_REMOTE = "encoder_int8_dynamic.tflite"
    private const val DECODER_REMOTE = "decoder_step_int8_dynamic.tflite"
    private const val SPIECE_REMOTE = "spiece.model"

    const val ENCODER_NAME = "encoder_int8_dynamic.tflite"
    const val DECODER_NAME = "decoder_step_int8_dynamic.tflite"
    const val SPIECE_NAME = "spiece.model"

    private val client by lazy { OkHttpClient() }

    /**
     * Ensures the encoder, decoder and tokenizer model are present under `files/models` and
     * returns their [File] locations. Downloads them individually if necessary.
     */
    suspend fun ensureModels(context: Context): Triple<File, File, File> = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val encoderFile = File(modelsDir, ENCODER_NAME)
        val decoderFile = File(modelsDir, DECODER_NAME)
        val spieceFile = File(modelsDir, SPIECE_NAME)

        if (!encoderFile.exists()) download(BASE_URL + ENCODER_REMOTE, encoderFile)
        if (!decoderFile.exists()) download(BASE_URL + DECODER_REMOTE, decoderFile)
        if (!spieceFile.exists()) download(BASE_URL + SPIECE_REMOTE, spieceFile)

        require(encoderFile.exists() && decoderFile.exists() && spieceFile.exists()) {
            "Failed to download model files"
        }

        Triple(encoderFile, decoderFile, spieceFile)
    }

    private fun download(url: String, dest: File) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${'$'}{resp.code}: ${'$'}url")
            resp.body?.byteStream().use { ins ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = ins?.read(buf) ?: -1
                        if (read == -1) break
                        out.write(buf, 0, read)
                    }
                }
            }
        }
    }

    // Legacy functions kept for potential future integrity checks.
    @Suppress("unused")
    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

