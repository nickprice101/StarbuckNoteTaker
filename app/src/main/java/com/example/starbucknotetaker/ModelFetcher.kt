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
 * Downloads the summarization models from a public endpoint on first run.
 * The models are stored under `files/models` in the app's internal storage.
 */
class ModelFetcher(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val DEFAULT_BASE_URL = "https://music.corsicanescape.com/apk/"

        private const val ENCODER_REMOTE = "encoder_int8_dynamic.tflite"
        private const val DECODER_REMOTE = "decoder_step_int8_dynamic.tflite"
        private const val SPIECE_REMOTE = "spiece.model"

        const val ENCODER_NAME = "encoder_int8_dynamic.tflite"
        const val DECODER_NAME = "decoder_step_int8_dynamic.tflite"
        const val SPIECE_NAME = "spiece.model"
    }

    sealed class Result {
        data class Success(val encoder: File, val decoder: File, val spiece: File) : Result()
        data class Failure(val message: String, val throwable: Throwable? = null) : Result()
    }

    /**
     * Ensures the encoder, decoder and tokenizer model are present under `files/models` and
     * returns their [File] locations. Downloads them individually if necessary.
     */
    suspend fun ensureModels(context: Context): Result =
        withContext(Dispatchers.IO) {
            val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
            val encoderFile = File(modelsDir, ENCODER_NAME)
            val decoderFile = File(modelsDir, DECODER_NAME)
            val spieceFile = File(modelsDir, SPIECE_NAME)

            try {
                if (!encoderFile.exists() || !isValidTflite(encoderFile)) {
                    encoderFile.delete()
                    download(baseUrl + ENCODER_REMOTE, encoderFile)
                }
                if (!decoderFile.exists() || !isValidTflite(decoderFile)) {
                    decoderFile.delete()
                    download(baseUrl + DECODER_REMOTE, decoderFile)
                }
                if (!spieceFile.exists() || spieceFile.length() == 0L) {
                    spieceFile.delete()
                    download(baseUrl + SPIECE_REMOTE, spieceFile)
                }

                return@withContext if (
                    encoderFile.exists() && decoderFile.exists() && spieceFile.exists()
                ) {
                    Result.Success(encoderFile, decoderFile, spieceFile)
                } else {
                    Result.Failure("Failed to download model files")
                }
            } catch (t: Throwable) {
                Result.Failure(t.message ?: "Failed to download model files", t)
            }
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

    private fun isValidTflite(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        file.inputStream().use { ins ->
            val header = ByteArray(4)
            val read = ins.read(header)
            return read == 4 &&
                header[0] == 'T'.code.toByte() &&
                header[1] == 'F'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == '3'.code.toByte()
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

