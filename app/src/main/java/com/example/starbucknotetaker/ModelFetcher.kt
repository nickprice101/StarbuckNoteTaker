package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Downloads the summarization models from a public endpoint on first run.
 * The models are stored under `files/models` in the app's internal storage.
 */
class ModelFetcher(
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        private const val DEFAULT_BASE_URL = "https://music.corsicanescape.com/apk/"

        const val ENCODER_REMOTE = "encoder_int8_dynamic.tflite"
        const val DECODER_REMOTE = "decoder_step_int8_dynamic.tflite"
        const val SPIECE_REMOTE = "spiece.model"

        const val ENCODER_NAME = ENCODER_REMOTE
        const val DECODER_NAME = DECODER_REMOTE
        const val SPIECE_NAME = SPIECE_REMOTE
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

            val haveModels = encoderFile.exists() && decoderFile.exists() && spieceFile.exists()
            val valid = haveModels && isValidTflite(encoderFile) && isValidTflite(decoderFile) && spieceFile.length() > 0L
            if (valid) {
                Log.d("Summarizer", "summarizer: model files already present")
                return@withContext Result.Success(encoderFile, decoderFile, spieceFile)
            }

            Log.d("Summarizer", "summarizer: scheduling model download")
            val work = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf("baseUrl" to baseUrl))
                .build()
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork("summarizer-model-download", ExistingWorkPolicy.KEEP, work)
            // getWorkInfoByIdFlow occasionally emits a null item before the
            // underlying WorkSpec is created. Cast to a nullable Flow and wait
            // until we have a finished WorkInfo to avoid a NPE.
            val finalInfo = @Suppress("UNCHECKED_CAST")
            (wm.getWorkInfoByIdFlow(work.id) as kotlinx.coroutines.flow.Flow<androidx.work.WorkInfo?>)
                .first { info -> info?.state?.isFinished == true }

            if (finalInfo == null || finalInfo.state != WorkInfo.State.SUCCEEDED) {
                val message = finalInfo?.outputData?.getString("error")
                    ?: "Failed to download model files"
                Log.e("Summarizer", "summarizer: model download failed: ${'$'}message")
                return@withContext Result.Failure(message)
            }

            val downloadedValid =
                encoderFile.exists() && decoderFile.exists() && spieceFile.exists() &&
                    isValidTflite(encoderFile) && isValidTflite(decoderFile) && spieceFile.length() > 0L

            return@withContext if (downloadedValid) {
                Log.d("Summarizer", "summarizer: model download complete")
                Result.Success(encoderFile, decoderFile, spieceFile)
            } else {
                Log.e("Summarizer", "summarizer: downloaded model files failed validation")
                Result.Failure("Downloaded model files failed validation")
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

