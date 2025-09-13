package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads the summarization models from GitHub Releases on first run.
 * The models are stored under `files/models` in the app's internal storage.
 */
object ModelFetcher {
    // URL of the ZIP file hosted on the repo's Releases page
    private const val ZIP_URL = "https://github.com/nickprice101/StarbuckNoteTaker/releases/download/v1.0.0/flan_t5_small_tflite_min.zip"
    private const val ZIP_NAME = "flan_t5_small_tflite_min.zip"
    // Optional SHA-256 of the ZIP for integrity checking
    private const val ZIP_SHA256 = "PUT_SHA256_OF_ZIP_HERE"

    // File names inside the ZIP archive
    const val ENCODER_NAME = "flan_t5_small_tflite_min/encoder_int8_dynamic.tflite"
    const val DECODER_NAME = "flan_t5_small_tflite_min/decoder_step_int8_dynamic.tflite"

    private val client by lazy { OkHttpClient() }

    /**
     * Ensures the encoder and decoder models are present under `files/models` and returns
     * their [File] locations. Downloads and unzips them if necessary.
     */
    suspend fun ensureModels(context: Context): Pair<File, File> = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val encoderFile = File(modelsDir, "encoder_int8_dynamic.tflite")
        val decoderFile = File(modelsDir, "decoder_step_int8_dynamic.tflite")

        if (encoderFile.exists() && decoderFile.exists()) return@withContext encoderFile to decoderFile

        val zipFile = File(context.cacheDir, ZIP_NAME)
        download(ZIP_URL, zipFile)

        if (ZIP_SHA256.isNotBlank()) {
            val got = sha256(zipFile)
            require(got.equals(ZIP_SHA256, ignoreCase = true)) {
                "Model ZIP SHA-256 mismatch. expected=$ZIP_SHA256 got=$got"
            }
        }

        unzipSelect(zipFile, modelsDir, setOf(ENCODER_NAME, DECODER_NAME))
        require(encoderFile.exists() && decoderFile.exists()) { "Model files missing after unzip" }
        zipFile.delete()
        encoderFile to decoderFile
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

    private fun unzipSelect(zip: File, dstDir: File, wanted: Set<String>) {
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name in wanted) {
                    val outFile = File(dstDir, name.substringAfterLast('/'))
                    outFile.outputStream().use { out ->
                        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zis.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

