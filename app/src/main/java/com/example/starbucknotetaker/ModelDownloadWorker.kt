package com.example.starbucknotetaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.content.pm.ServiceInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder()
        .callTimeout(2, TimeUnit.MINUTES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 1_000L

        private val EXPECTED_FILES = mapOf(
            ModelFetcher.ENCODER_NAME to ExpectedFile(
                kind = FileKind.TFLITE,
                sizeBytes = 35_986_000L,
                sha256 = "e38daf1645972bbf8e096dc5f2c030c17f5114d4295d2161f59d1b0a8261091c"
            ),
            ModelFetcher.DECODER_NAME to ExpectedFile(
                kind = FileKind.TFLITE,
                sizeBytes = 59_264_176L,
                sha256 = "34a39fac38e371888f3a2bf79ce37c938e226252f37f41ff6ab79ce09922c4df"
            ),
            ModelFetcher.SPIECE_NAME to ExpectedFile(
                kind = FileKind.SENTENCE_PIECE,
                sizeBytes = 791_656L,
                sha256 = "d60acb128cf7b7f2536e8f38a5b18a05535c9e14c7a355904270e15b0945ea86"
            )
        )
    }

    private data class ExpectedFile(
        val kind: FileKind,
        val sizeBytes: Long,
        val sha256: String
    )

    private enum class FileKind { TFLITE, SENTENCE_PIECE }

    override suspend fun doWork(): Result {
        val baseUrl = inputData.getString("baseUrl") ?: return Result.failure()
        Log.d("Summarizer", "summarizer: starting background model download")
        val modelsDir = File(applicationContext.filesDir, "models").apply { mkdirs() }

        val tasks = listOf(
            ModelFetcher.ENCODER_NAME to ModelFetcher.ENCODER_REMOTE,
            ModelFetcher.DECODER_NAME to ModelFetcher.DECODER_REMOTE,
            ModelFetcher.SPIECE_NAME to ModelFetcher.SPIECE_REMOTE
        )

        var progress = 0
        setForeground(createForegroundInfo(progress))
        for ((name, remote) in tasks) {
            val url = baseUrl + remote
            val dest = File(modelsDir, name)
            try {
                Log.d("Summarizer", "summarizer: downloading $name")
                download(name, url, dest)
                progress += 100 / tasks.size
                setForeground(createForegroundInfo(progress))
                Log.d("Summarizer", "summarizer: downloaded $name")
            } catch (t: Throwable) {
                Log.e("Summarizer", "summarizer: failed downloading $name", t)
                return Result.failure(
                    workDataOf(
                        "error" to (t.message ?: t::class.java.simpleName ?: "download failed"),
                        "file" to name
                    )
                )
            }
        }
        setForeground(createForegroundInfo(100))
        Log.d("Summarizer", "summarizer: model download finished")
        return Result.success()
    }

    private suspend fun download(name: String, url: String, dest: File) {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder().url(url).get().build()
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, "${dest.name}.download")
                if (tmp.exists()) tmp.delete()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: $url")
                    val body = resp.body ?: error("Empty body: $url")
                    body.use { responseBody ->
                        FileOutputStream(tmp).use { out ->
                            responseBody.byteStream().use { ins ->
                                ins.copyTo(out)
                            }
                        }
                    }
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                if (!verifyDownloadedFile(name, dest)) {
                    dest.delete()
                    throw IllegalStateException("Integrity check failed for $name")
                }
                return
            } catch (t: Throwable) {
                lastError = t
                dest.delete()
                val tmp = File(dest.parentFile, "${dest.name}.download")
                tmp.delete()
                if (attempt < MAX_ATTEMPTS - 1) {
                    val delayMs = RETRY_BASE_DELAY_MS shl attempt
                    Log.w(
                        "Summarizer",
                        "summarizer: retrying download $url in $delayMs ms (attempt ${attempt + 2} of $MAX_ATTEMPTS)",
                        t
                    )
                    delay(delayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Failed to download $url")
    }

    private fun verifyDownloadedFile(name: String, file: File): Boolean {
        if (!file.exists()) {
            Log.e("Summarizer", "summarizer: downloaded file missing for $name")
            return false
        }

        val expected = EXPECTED_FILES[name]
        val kind = expected?.kind ?: inferKind(name)
        var valid = true

        val size = file.length()
        if (size <= 0) {
            Log.e("Summarizer", "summarizer: size mismatch for $name (empty file)")
            valid = false
        }

        if (expected != null && size != expected.sizeBytes) {
            Log.e(
                "Summarizer",
                "summarizer: size mismatch for $name (expected ${expected.sizeBytes} bytes, actual $size bytes)"
            )
            valid = false
        }

        if (kind == FileKind.TFLITE && !ModelFetcher.hasTfliteMagic(file)) {
            Log.e(
                "Summarizer",
                "summarizer: invalid TFLite header for $name (first bytes: ${headerPreview(file)})"
            )
            valid = false
        }

        if (expected != null) {
            val actualHash = sha256(file)
            if (!actualHash.equals(expected.sha256, ignoreCase = true)) {
                Log.e(
                    "Summarizer",
                    "summarizer: sha256 mismatch for $name (expected ${expected.sha256}, actual $actualHash)"
                )
                valid = false
            }
        }

        if (valid) {
            Log.d(
                "Summarizer",
                "summarizer: integrity check passed for $name ($size bytes)"
            )
        }

        return valid
    }

    private fun inferKind(name: String): FileKind =
        if (name.endsWith(".tflite", ignoreCase = true)) FileKind.TFLITE else FileKind.SENTENCE_PIECE

    private fun headerPreview(file: File, byteCount: Int = 8): String {
        val header = ByteArray(byteCount)
        val read = file.inputStream().use { it.read(header) }
        if (read <= 0) return ""
        return header.take(read).joinToString(separator = " ") { b ->
            "%02x".format(b.toInt() and 0xff)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = ins.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val channelId = "summarizer_download"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "Summarizer Download",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Downloading summarizer")
            .setContentText("summarizer model download")
            .setSmallIcon(R.drawable.ic_notepad)
            .setProgress(100, progress, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                42,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(42, notification)
        }
    }
}
