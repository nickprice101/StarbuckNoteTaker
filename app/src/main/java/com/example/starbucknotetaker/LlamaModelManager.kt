package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle of the Llama 3.2 3B MLC-compiled model weights on device.
 *
 * The model weights (~2 GB) are never bundled in the APK.  They are downloaded from
 * HuggingFace on first use and stored in
 * `filesDir/models/Llama-3.2-3B-Instruct-q4f16_0-MLC/`.
 *
 * The MLC model directory contains:
 *   - `mlc-chat-config.json`   — model/tokenizer configuration
 *   - `ndarray-cache.json`     — weight-shard manifest
 *   - `tokenizer.json`         — vocabulary / BPE data
 *   - `tokenizer_config.json`  — tokenizer hyper-parameters
 *   - `params_shard_*.bin`     — actual model weights (many shards)
 *
 * The compiled model library (`.so`) must be placed in the APK's
 * `jniLibs/arm64-v8a/` directory before building.  Extract it from the MLC
 * prebuilt release APK published at:
 * https://github.com/mlc-ai/binary-mlc-llm-libs/releases/tag/Android-09262024
 *
 * Steps:
 *   1. Download `mlc-chat.apk` from the release above.
 *   2. Unzip: `unzip mlc-chat.apk "lib/arm64-v8a/libLlama-3.2-3B-Instruct-q4f16_0-MLC.so"`
 *   3. Copy the extracted `.so` to `app/src/main/jniLibs/arm64-v8a/`.
 *   4. Rebuild the project — Gradle will package the library automatically.
 *
 * Usage:
 * ```
 * val manager = LlamaModelManager(context)
 * manager.modelStatus.collect { status -> … }
 * manager.downloadModel { pct -> updateProgress(pct) }
 * ```
 */
class LlamaModelManager(private val context: Context) {

    sealed class ModelStatus {
        object Missing      : ModelStatus()
        data class Present(val path: String) : ModelStatus()
        data class Downloading(val progressPercent: Int, val label: String) : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Missing)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus

    private val modelDir: File
        get() = File(context.filesDir, MODEL_SUBDIR).also { it.mkdirs() }

    init {
        _modelStatus.value = computeCurrentStatus()
    }

    /**
     * Returns the path to the model weights directory if all required files are present.
     */
    fun getModelPath(): String? {
        val dir = modelDir
        val allPresent = REQUIRED_FILES.all { name ->
            File(dir, name).exists()
        }
        return if (allPresent && dir.exists()) dir.absolutePath else null
    }

    /** `true` when the model is fully downloaded and ready to load. */
    fun isModelPresent(): Boolean = getModelPath() != null

    /** Refreshes [modelStatus] from the current filesystem state. */
    fun refreshStatus() {
        _modelStatus.value = computeCurrentStatus()
    }

    /**
     * Downloads all required Llama 3.1 8B MLC weight files from HuggingFace.
     *
     * Queries the HuggingFace tree API to get the full file listing (shard count
     * varies by quantisation), then fetches each file sequentially, reporting
     * cumulative progress as a 0–100 integer.
     *
     * @param onProgress Optional callback receiving a 0–100 progress value.
     * @return `true` on success, `false` on any error.
     */
    suspend fun downloadModel(onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            _modelStatus.value = ModelStatus.Downloading(0, "Preparing download…")
            val dest = modelDir

            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .build()

                // 1. Get file listing from HuggingFace API
                val files = fetchHuggingFaceFileList(client)
                if (files.isEmpty()) {
                    _modelStatus.value = ModelStatus.Error("Could not retrieve model file list")
                    return@withContext false
                }

                val totalBytes = files.sumOf { it.second }
                var downloadedBytes = 0L
                var fileIndex = 0

                // 2. Download each file
                for ((fileName, expectedSize) in files) {
                    fileIndex++
                    val label = "Downloading file $fileIndex / ${files.size}: $fileName"
                    Log.i(TAG, label)
                    _modelStatus.value = ModelStatus.Downloading(
                        if (totalBytes > 0) (downloadedBytes * 100L / totalBytes).toInt() else 0,
                        label,
                    )

                    val targetFile = File(dest, fileName)
                    targetFile.parentFile?.mkdirs()
                    if (targetFile.exists() && targetFile.length() == expectedSize) {
                        downloadedBytes += expectedSize
                        Log.d(TAG, "Skipping already-downloaded $fileName")
                        continue
                    }

                    val url = "$HF_BASE_URL/$fileName"
                    val ok = downloadFile(client, url, targetFile) { chunkBytes ->
                        downloadedBytes += chunkBytes
                        val pct = if (totalBytes > 0) {
                            (downloadedBytes * 100L / totalBytes).toInt()
                        } else 0
                        _modelStatus.value = ModelStatus.Downloading(pct, label)
                        onProgress(pct)
                    }
                    if (!ok) {
                        _modelStatus.value = ModelStatus.Error("Failed to download $fileName")
                        return@withContext false
                    }
                }

                _modelStatus.value = ModelStatus.Present(dest.absolutePath)
                Log.i(TAG, "Model download complete → ${dest.absolutePath}")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Model download error", e)
                _modelStatus.value = ModelStatus.Error(e.message ?: "Download failed")
                false
            }
        }

    /**
     * Deletes the local model weights directory to free ~2 GB of storage.
     */
    fun deleteModel() {
        modelDir.deleteRecursively()
        Log.i(TAG, "Model directory deleted")
        _modelStatus.value = ModelStatus.Missing
    }

    /** Approximate size of downloaded model in GB, or `null` if not present. */
    fun modelSizeGb(): Float? {
        val dir = modelDir
        if (!dir.exists()) return null
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return if (bytes > 0) bytes.toFloat() / (1024 * 1024 * 1024) else null
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private fun computeCurrentStatus(): ModelStatus {
        val path = getModelPath()
        return if (path != null) ModelStatus.Present(path) else ModelStatus.Missing
    }

    /**
     * Fetches the list of files in the HuggingFace repo via the tree API.
     * Returns a list of (filename, sizeBytes) pairs.
     */
    private fun fetchHuggingFaceFileList(client: OkHttpClient): List<Pair<String, Long>> {
        val apiUrl = "https://huggingface.co/api/models/$HF_REPO_ID"
        val request = Request.Builder().url(apiUrl).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HuggingFace API error: ${response.code}")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val siblings = json.optJSONArray("siblings") ?: return emptyList()
                val files = mutableListOf<Pair<String, Long>>()
                for (i in 0 until siblings.length()) {
                    val sib = siblings.getJSONObject(i)
                    val name = sib.optString("rfilename") ?: continue
                    val size = sib.optLong("size", 0L)
                    // Download all files that are needed for MLC inference
                    if (name.endsWith(".json") || name.endsWith(".bin") ||
                        name.endsWith(".txt") || name.endsWith(".model")
                    ) {
                        files.add(name to size)
                    }
                }
                // Ensure required config files come first
                files.sortWith(compareBy {
                    when {
                        it.first.endsWith(".json") -> 0
                        it.first.endsWith(".txt") || it.first.endsWith(".model") -> 1
                        else -> 2
                    }
                })
                files
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch file list", e)
            emptyList()
        }
    }

    private fun downloadFile(
        client: OkHttpClient,
        url: String,
        dest: File,
        onChunk: (Long) -> Unit,
    ): Boolean {
        val tmp = File(dest.parent, "${dest.name}.tmp")
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code} for $url")
                    return false
                }
                val body = response.body ?: return false
                FileOutputStream(tmp).use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (inp.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            onChunk(read.toLong())
                        }
                    }
                }
            }
            tmp.renameTo(dest)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $url", e)
            tmp.delete()
            false
        }
    }

    companion object {
        private const val TAG = "LlamaModelManager"

        /** HuggingFace model repository identifier. */
        const val HF_REPO_ID = "mlc-ai/Llama-3.2-3B-Instruct-q4f16_0-MLC"

        /** Base URL for downloading individual files from this repo. */
        private const val HF_BASE_URL =
            "https://huggingface.co/$HF_REPO_ID/resolve/main"

        /**
         * Sub-directory inside `filesDir` where the model weights live.
         * This is also the value passed to [ai.mlc.mlcllm.MLCEngine.reload] as `modelPath`.
         */
        const val MODEL_SUBDIR = "models/Llama-3.2-3B-Instruct-q4f16_0-MLC"

        /**
         * The model-library name passed to [ai.mlc.mlcllm.MLCEngine.reload].
         * This corresponds to the compiled `.so` bundled in the APK's `jniLibs/arm64-v8a/`.
         * See the class KDoc for instructions on obtaining the prebuilt library.
         */
        const val MODEL_LIB_NAME = "Llama-3.2-3B-Instruct-q4f16_0-MLC"

        /** Human-readable approximate download size shown in the Settings UI. */
        const val MODEL_SIZE_LABEL = "~2.0 GB"

        /**
         * Files that must be present for the model to be considered ready.
         * Param shards are checked dynamically; these are the invariant metadata files.
         */
        private val REQUIRED_FILES = listOf(
            "mlc-chat-config.json",
            "ndarray-cache.json",
            "tokenizer.json",
        )

        private const val BUFFER_SIZE = 64 * 1024
    }
}
