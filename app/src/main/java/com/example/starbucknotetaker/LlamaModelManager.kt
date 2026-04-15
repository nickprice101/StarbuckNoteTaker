package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle of the on-device GGUF model file used by [LlamaEngine].
 *
 * The model is never bundled in the APK (it can be 100 MB+).  Instead it is
 * downloaded on first use and stored in `filesDir/models/llama/`.  Users can
 * also delete the file to reclaim storage from the Settings screen.
 *
 * Usage:
 * ```
 * val manager = LlamaModelManager(context)
 * manager.modelStatus.collect { status -> ... }
 * manager.downloadModel(MODEL_URL) { pct -> updateProgress(pct) }
 * ```
 */
class LlamaModelManager(private val context: Context) {

    sealed class ModelStatus {
        object Missing : ModelStatus()
        data class Present(val path: String) : ModelStatus()
        data class Downloading(val progressPercent: Int) : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Missing)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus

    private val modelsDir: File
        get() = File(context.filesDir, MODELS_SUBDIR).also { it.mkdirs() }

    private val modelFile: File
        get() = File(modelsDir, MODEL_FILENAME)

    init {
        _modelStatus.value = computeCurrentStatus()
    }

    /** @return the absolute path to the model file if it exists, else `null`. */
    fun getModelPath(): String? {
        val file = modelFile
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /** @return `true` if a valid model file is present on disk. */
    fun isModelPresent(): Boolean = getModelPath() != null

    /** Refreshes [modelStatus] from the current filesystem state. */
    fun refreshStatus() {
        _modelStatus.value = computeCurrentStatus()
    }

    /**
     * Downloads the model from [url] into the models directory, reporting
     * download progress as a 0–100 integer via [onProgress].
     *
     * @return `true` on success, `false` on failure.
     */
    suspend fun downloadModel(
        url: String,
        onProgress: (Int) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        _modelStatus.value = ModelStatus.Downloading(0)
        val dest = modelFile
        val tmp  = File(modelsDir, "${MODEL_FILENAME}.tmp")
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Model download failed: HTTP ${response.code}")
                    _modelStatus.value = ModelStatus.Error("HTTP ${response.code}")
                    return@withContext false
                }

                val body = response.body ?: run {
                    _modelStatus.value = ModelStatus.Error("Empty response body")
                    return@withContext false
                }

                val contentLength = body.contentLength()
                var bytesRead = 0L
                var lastReportedPct = -1

                FileOutputStream(tmp).use { out ->
                    body.byteStream().use { inp ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (inp.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            bytesRead += read
                            if (contentLength > 0) {
                                val pct = (bytesRead * 100L / contentLength).toInt()
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    _modelStatus.value = ModelStatus.Downloading(pct)
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }

            tmp.renameTo(dest)
            _modelStatus.value = ModelStatus.Present(dest.absolutePath)
            Log.i(TAG, "Model downloaded to ${dest.absolutePath} (${dest.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model download error", e)
            tmp.delete()
            _modelStatus.value = ModelStatus.Error(e.message ?: "Download failed")
            false
        }
    }

    /**
     * Deletes the local model file to free storage.
     * [modelStatus] transitions to [ModelStatus.Missing] on success.
     */
    fun deleteModel() {
        val file = modelFile
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "Model file deleted")
        }
        _modelStatus.value = ModelStatus.Missing
    }

    /** Approximate size of the model file in MB, or `null` if not present. */
    fun modelSizeMb(): Float? {
        val file = modelFile
        return if (file.exists()) file.length().toFloat() / (1024 * 1024) else null
    }

    private fun computeCurrentStatus(): ModelStatus {
        val path = getModelPath()
        return if (path != null) ModelStatus.Present(path) else ModelStatus.Missing
    }

    companion object {
        private const val TAG = "LlamaModelManager"
        private const val MODELS_SUBDIR = "models/llama"
        const val MODEL_FILENAME = "model.gguf"
        private const val BUFFER_SIZE = 8 * 1024

        /**
         * Default download URL for the bundled default model.
         * Override via [AppSettings] or pass a custom URL to [downloadModel].
         *
         * SmolLM2-135M-Instruct Q4_K_M is a ~90 MB quantised instruction-tuned
         * model that runs well on-device for summarisation, rewrite and Q&A.
         */
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/" +
                "SmolLM2-135M-Instruct-Q4_K_M.gguf"

        /** Human-readable approximate size string shown in the Settings UI. */
        const val DEFAULT_MODEL_SIZE_LABEL = "~90 MB"
    }
}
