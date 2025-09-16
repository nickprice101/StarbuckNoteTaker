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
    }

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
                Log.d("Summarizer", "summarizer: downloading ${'$'}name")
                download(url, dest)
                progress += 100 / tasks.size
                setForeground(createForegroundInfo(progress))
                Log.d("Summarizer", "summarizer: downloaded ${'$'}name")
            } catch (t: Throwable) {
                Log.e("Summarizer", "summarizer: failed downloading ${'$'}name", t)
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

    private suspend fun download(url: String, dest: File) {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder().url(url).get().build()
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, "${'$'}{dest.name}.download")
                if (tmp.exists()) tmp.delete()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${'$'}{resp.code}: ${'$'}url")
                    val body = resp.body ?: error("Empty body: ${'$'}url")
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
                return
            } catch (t: Throwable) {
                lastError = t
                dest.delete()
                val tmp = File(dest.parentFile, "${'$'}{dest.name}.download")
                tmp.delete()
                if (attempt < MAX_ATTEMPTS - 1) {
                    val delayMs = RETRY_BASE_DELAY_MS shl attempt
                    Log.w(
                        "Summarizer",
                        "summarizer: retrying download ${'$'}url in ${'$'}delayMs ms (attempt ${'$'}{attempt + 2} of ${'$'}MAX_ATTEMPTS)",
                        t
                    )
                    delay(delayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Failed to download ${'$'}url")
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
