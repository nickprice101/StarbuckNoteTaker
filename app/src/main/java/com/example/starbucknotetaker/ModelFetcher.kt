package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object ModelFetcher {
    private const val ENCODER_URL =
        "https://github.com/nickprice101/StarbuckNoteTaker/releases/download/v1.0.0/encoder_int8_dynamic.tflite"
    private const val DECODER_URL =
        "https://github.com/nickprice101/StarbuckNoteTaker/releases/download/v1.0.0/decoder_step_int8_dynamic.tflite"

    // Optional SHA-256 hashes; leave blank to skip verification
    private const val ENCODER_SHA256 = ""
    private const val DECODER_SHA256 = ""

    private val client by lazy { OkHttpClient() }

    /** Ensures the models exist under filesDir/models and returns their File paths. */
    suspend fun ensureModels(context: Context): Pair<File, File> = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val encoderFile = File(modelsDir, "encoder_int8_dynamic.tflite")
        val decoderFile = File(modelsDir, "decoder_step_int8_dynamic.tflite")

        if (!encoderFile.exists()) {
            download(ENCODER_URL, encoderFile)
            if (ENCODER_SHA256.isNotBlank()) {
                val got = sha256(encoderFile)
                require(got.equals(ENCODER_SHA256, ignoreCase = true)) {
                    "Encoder model SHA-256 mismatch. expected=$ENCODER_SHA256 got=$got"
                }
            }
        }

        if (!decoderFile.exists()) {
            download(DECODER_URL, decoderFile)
            if (DECODER_SHA256.isNotBlank()) {
                val got = sha256(decoderFile)
                require(got.equals(DECODER_SHA256, ignoreCase = true)) {
                    "Decoder model SHA-256 mismatch. expected=$DECODER_SHA256 got=$got"
                }
            }
        }

        require(encoderFile.exists() && decoderFile.exists()) {
            "Model files missing after download"
        }

        encoderFile to decoderFile
    }

    private fun download(url: String, dest: File) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $url")
            resp.body?.byteStream().use { inStream ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = inStream?.read(buf) ?: -1
                        if (read == -1) break
                        out.write(buf, 0, read)
                    }
                }
            }
        }
    }

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
