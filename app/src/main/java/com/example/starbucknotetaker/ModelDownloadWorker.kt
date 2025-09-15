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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient()

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
                return Result.failure()
            }
        }
        setForeground(createForegroundInfo(100))
        Log.d("Summarizer", "summarizer: model download finished")
        return Result.success()
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

        return ForegroundInfo(42, notification)
    }
}
