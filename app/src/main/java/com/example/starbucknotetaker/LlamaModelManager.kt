package com.example.starbucknotetaker

import android.content.Context
import android.os.Build
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
 * **Modern `.tar` flow:**
 * The compiled model library is distributed as [TAR_ASSET_NAME], bundled inside
 * the APK's `assets/` directory.  The `.tar` contains the MLC system-library object
 * files (`lib0.o`, `llama_q4f16_0_devc.o`) that the TVM runtime links at runtime.
 * On the first call to [extractModelLibIfNeeded] the archive is extracted to
 * `filesDir/[MODEL_LIB_EXTRACT_SUBDIR]/` and that directory path is passed to
 * [ai.mlc.mlcllm.MLCEngine.reload] as `modelLib` instead of a JNI library name.
 *
 * Usage:
 * ```
 * val manager = LlamaModelManager(context)
 * manager.modelStatus.collect { status -> … }
 * val libPath = manager.extractModelLibIfNeeded()   // extract .tar on first launch
 * manager.downloadModel { pct -> updateProgress(pct) }
 * ```
 */
class LlamaModelManager(private val context: Context) {

    sealed class ModelStatus {
        object Missing      : ModelStatus()
        data class Present(val path: String, val sizeBytes: Long = 0L, val abi: String = UNKNOWN_ABI) : ModelStatus()
        data class Downloading(
            val progressPercent: Int,
            val label: String,
            val downloadedBytes: Long = 0L,
            val totalBytes: Long = 0L,
            val abi: String = UNKNOWN_ABI,
        ) : ModelStatus()
        data class Unsupported(
            val abi: String,
            val supportedAbis: List<String>,
            val message: String,
        ) : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Missing)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus

    private val runtimeAbi: String
        get() = selectRuntimeAbi(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
        )

    private val runtimeProfile: RuntimeModelProfile?
        get() = resolveRuntimeProfile(runtimeAbi)

    private val modelDir: File
        get() = modelDirFor(runtimeProfile)

    init {
        _modelStatus.value = computeCurrentStatus()
    }

    /**
     * Returns the path to the model weights directory if all required files are present,
     * including at least one weight-shard `.bin` file.
     *
     * Checking only the metadata JSON files is insufficient: a partial download that fetched
     * the config/tokenizer files but not the weight shards would pass a JSON-only check and
     * cause the Settings UI to falsely show "Model ready" while inference would still fail.
     */
    fun getModelPath(): String? {
        val profile = runtimeProfile ?: return null
        return getModelPath(profile)
    }

    private fun getModelPath(profile: RuntimeModelProfile): String? {
        val dir = modelDirFor(profile)
        val metaPresent = REQUIRED_FILES.all { name -> File(dir, name).exists() }
        if (!metaPresent) return null
        val hasWeightShard = dir.listFiles()?.any { it.isFile && it.name.endsWith(".bin") } == true
        return if (hasWeightShard) dir.absolutePath else null
    }

    /** `true` when the model is fully downloaded and ready to load. */
    fun isModelPresent(): Boolean = getModelPath() != null

    /** Refreshes [modelStatus] from the current filesystem state. */
    fun refreshStatus() {
        _modelStatus.value = computeCurrentStatus()
    }

    /**
     * Downloads all required Llama 3.2 3B MLC weight files from HuggingFace.
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
            val profile = runtimeProfile
            if (profile == null) {
                val unsupported = unsupportedStatus()
                _modelStatus.value = unsupported
                Log.w(TAG, unsupported.message)
                return@withContext false
            }

            _modelStatus.value = ModelStatus.Downloading(
                0,
                "Preparing ${profile.abi} download…",
                0L,
                0L,
                profile.abi,
            )
            val dest = modelDirFor(profile)

            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .build()

                // 1. Get file listing from HuggingFace API
                val files = fetchHuggingFaceFileList(client, profile)
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
                        downloadedBytes,
                        totalBytes,
                        profile.abi,
                    )

                    val targetFile = File(dest, fileName)
                    targetFile.parentFile?.mkdirs()
                    if (targetFile.exists() && targetFile.length() == expectedSize) {
                        downloadedBytes += expectedSize
                        Log.d(TAG, "Skipping already-downloaded $fileName")
                        continue
                    }

                    val url = profile.fileUrl(fileName)
                    val ok = downloadFile(client, url, targetFile) { chunkBytes ->
                        downloadedBytes += chunkBytes
                        val pct = if (totalBytes > 0) {
                            (downloadedBytes * 100L / totalBytes).toInt()
                        } else 0
                        _modelStatus.value = ModelStatus.Downloading(
                            pct,
                            label,
                            downloadedBytes,
                            totalBytes,
                            profile.abi,
                        )
                        onProgress(pct)
                    }
                    if (!ok) {
                        _modelStatus.value = ModelStatus.Error("Failed to download $fileName")
                        return@withContext false
                    }
                }

                val sizeBytes = dest.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                _modelStatus.value = ModelStatus.Present(dest.absolutePath, sizeBytes, profile.abi)
                Log.i(TAG, "Model download complete → ${dest.absolutePath} (${sizeBytes / (1024 * 1024)} MB)")
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
        MODEL_PROFILES.values
            .map { modelDirFor(it) }
            .distinctBy { it.absolutePath }
            .forEach { it.deleteRecursively() }
        modelDirFor(null).deleteRecursively()
        Log.i(TAG, "Model directory deleted")
        _modelStatus.value = computeCurrentStatus()
    }

    /**
     * Extracts the bundled model library `.tar` from APK assets into internal storage
     * if it has not already been extracted.
     *
     * The `.tar` (see [TAR_ASSET_NAME]) is packaged inside the APK's `assets/`
     * directory and contains the compiled MLC system-library object files
     * (`lib0.o`, `llama_q4f16_0_devc.o`).  Extraction is skipped when the
     * sentinel file `[MODEL_LIB_EXTRACT_SUBDIR]/.extracted` already exists.
     *
     * @return The absolute path to the extracted library directory, or `null` on failure.
     */
    fun extractModelLibIfNeeded(): String? {
        val profile = runtimeProfile ?: run {
            val unsupported = unsupportedStatus()
            _modelStatus.value = unsupported
            Log.w(TAG, unsupported.message)
            return null
        }
        val libDir = File(context.filesDir, profile.modelLibExtractSubdir)
        val sentinel = File(libDir, ".extracted")
        // Only skip extraction when the sentinel exists AND the expected object files are present,
        // so a partial or corrupt previous extraction is always retried.
        if (sentinel.exists() && REQUIRED_LIB_FILES.all { File(libDir, it).exists() }) {
            Log.d(TAG, "Model lib already extracted at ${libDir.absolutePath}")
            return libDir.absolutePath
        }
        return try {
            Log.i(TAG, "Extracting model lib from asset ${profile.tarAssetName} → ${libDir.absolutePath}")
            libDir.deleteRecursively()
            libDir.mkdirs()
            context.assets.open(profile.tarAssetName).use { input ->
                TarExtractor.extract(input, libDir)
            }
            // Verify extraction before writing the sentinel so a corrupt archive is retried.
            val missing = REQUIRED_LIB_FILES.filterNot { File(libDir, it).exists() }
            if (missing.isNotEmpty()) {
                Log.e(TAG, "Model lib extraction incomplete — missing: $missing")
                return null
            }
            sentinel.createNewFile()
            Log.i(TAG, "Model lib extraction complete")
            libDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract model lib", e)
            null
        }
    }

    /** Approximate size of downloaded model in GB, or `null` if not present. */
    fun modelSizeGb(): Float? {
        val profile = runtimeProfile ?: return null
        val dir = modelDirFor(profile)
        if (!dir.exists()) return null
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return if (bytes > 0) bytes.toFloat() / (1024 * 1024 * 1024) else null
    }

    /**
     * Returns a multi-line debug string describing the state of the model and lib directories.
     * Intended to be included in error messages and log output when inference fails.
     */
    fun debugModelDirInfo(): String {
        val sb = StringBuilder()
        val profile = runtimeProfile
        sb.appendLine(
            "Runtime ABI: $runtimeAbi (supported=${profile != null}, " +
                "supportedAbis=${SUPPORTED_MODEL_ABIS.joinToString()})"
        )
        val dir = modelDir
        sb.appendLine("Model dir: ${dir.absolutePath} (exists=${dir.exists()})")
        if (dir.exists()) {
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            sb.appendLine("  Files (${files.size}):")
            files.forEach { f ->
                val sizeMb = f.length() / (1024.0 * 1024.0)
                sb.appendLine("    ${f.name}  %.2f MB".format(sizeMb))
            }
        }
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val modelSo = java.io.File(nativeLibDir, MODEL_SO_FILENAME)
        sb.appendLine("Model .so: ${modelSo.absolutePath} (exists=${modelSo.exists()}," +
            " size=${if (modelSo.exists()) "%.1f MB".format(modelSo.length() / (1024.0 * 1024.0)) else "n/a"})")
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private fun computeCurrentStatus(): ModelStatus {
        val profile = runtimeProfile ?: return unsupportedStatus()
        val path = getModelPath(profile) ?: return ModelStatus.Missing
        val dir = modelDirFor(profile)
        val sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ModelStatus.Present(path, sizeBytes, profile.abi)
    }

    private fun unsupportedStatus(): ModelStatus.Unsupported {
        val abi = runtimeAbi
        return ModelStatus.Unsupported(
            abi = abi,
            supportedAbis = SUPPORTED_MODEL_ABIS,
            message = "The installed native runtime is '$abi', but this build only has " +
                "Llama native artifacts for ${SUPPORTED_MODEL_ABIS.joinToString()}. " +
                "Use an ${SUPPORTED_MODEL_ABIS.first()} build/device or add a matching " +
                "MLC runtime and compiled model library for '$abi'.",
        )
    }

    private fun modelDirFor(profile: RuntimeModelProfile?): File =
        File(context.filesDir, profile?.modelSubdir ?: MODEL_SUBDIR).also { it.mkdirs() }

    /**
     * Fetches the list of files in the HuggingFace repo via the tree API.
     * Returns a list of (filename, sizeBytes) pairs.
     */
    private fun fetchHuggingFaceFileList(
        client: OkHttpClient,
        profile: RuntimeModelProfile,
    ): List<Pair<String, Long>> {
        val apiUrl = "https://huggingface.co/api/models/${profile.hfRepoId}"
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

    private data class RuntimeModelProfile(
        val abi: String,
        val hfRepoId: String,
        val modelSubdir: String,
        val tarAssetName: String,
        val modelLibExtractSubdir: String,
    ) {
        fun fileUrl(fileName: String): String =
            "https://huggingface.co/$hfRepoId/resolve/main/$fileName"
    }

    companion object {
        private const val TAG = "LlamaModelManager"
        const val UNKNOWN_ABI = "unknown"

        /**
         * Asset file name of the `.tar` that bundles the compiled MLC model library.
         * The archive is packaged in `assets/` and extracted at runtime by
         * [extractModelLibIfNeeded].
         */
        const val TAR_ASSET_NAME = "Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar"

        /**
         * Sub-directory inside `filesDir` where the model library `.tar` is extracted.
         * The absolute path to this directory is passed to [ai.mlc.mlcllm.MLCEngine.reload]
         * as `modelLib` in the modern `.tar` flow.
         */
        const val MODEL_LIB_EXTRACT_SUBDIR = "lib/Llama-3.2-3B-Instruct-q4f16_0-MLC-android"

        /** HuggingFace model repository identifier. */
        const val HF_REPO_ID = "mlc-ai/Llama-3.2-3B-Instruct-q4f16_0-MLC"

        /**
         * Sub-directory inside `filesDir` where the model weights live.
         * This is also the value passed to [ai.mlc.mlcllm.MLCEngine.reload] as `modelPath`.
         */
        const val MODEL_SUBDIR = "models/Llama-3.2-3B-Instruct-q4f16_0-MLC"

        /**
         * The Android library name for the compiled model kernel library.
         *
         * This is the bare name (without the `lib` prefix or `.so` suffix) used to
         * derive [MODEL_SO_FILENAME] and [MODEL_LIB_SYSTEM_HANDLE].
         */
        const val MODEL_LIB_NAME = "Llama-3.2-3B-Instruct-q4f16_0-MLC"

        /**
         * The filename of the compiled model kernel library bundled in the APK's
         * `jniLibs/arm64-v8a/` directory.
         *
         * Android extracts this file to {@code applicationInfo.nativeLibraryDir} at install time.
         * The library is loaded via {@code System.load(path)} so that the model kernel functions
         * are registered with TVM's global system-lib registry via the {@code tvm_compat.c} shim.
         *
         * The library is built by the Gradle task `buildModelLibSo`, which links the
         * `.o` files from [TAR_ASSET_NAME] together with a TVM API compatibility shim
         * into a single `.so` that works with the bundled `libtvm4j_runtime_packed.so`.
         */
        const val MODEL_SO_FILENAME = "lib$MODEL_LIB_NAME.so"

        /**
         * The system-library handle passed to [ai.mlc.mlcllm.MLCEngine.reload] as `modelLib`.
         *
         * After the model kernel library has been loaded via {@code System.load} (which registers
         * all kernel functions with TVM's system-lib registry), MLC-LLM must be told to access
         * those functions through the system-lib mechanism rather than via TVM's file loader
         * (which would require `runtime.module.loadfile_so` — not available in the bundled
         * Android TVM runtime).  Passing the `system://` prefix instructs MLC-LLM to call
         * TVM's `runtime.SystemLib()` and retrieve the pre-registered module.
         */
        const val MODEL_LIB_SYSTEM_HANDLE = "system://$MODEL_LIB_NAME"

        /** Human-readable approximate download size shown in the Settings UI. */
        const val MODEL_SIZE_LABEL = "~2.0 GB"

        /** Human-readable model name used in request metadata. */
        const val MODEL_DISPLAY_NAME = "Llama-3.2-3B-Instruct-q4f16_0-MLC"

        private const val ABI_ARM64_V8A = "arm64-v8a"
        val SUPPORTED_MODEL_ABIS: List<String> = listOf(ABI_ARM64_V8A)

        private val MODEL_PROFILES: Map<String, RuntimeModelProfile> = mapOf(
            ABI_ARM64_V8A to RuntimeModelProfile(
                abi = ABI_ARM64_V8A,
                hfRepoId = HF_REPO_ID,
                modelSubdir = MODEL_SUBDIR,
                tarAssetName = TAR_ASSET_NAME,
                modelLibExtractSubdir = MODEL_LIB_EXTRACT_SUBDIR,
            )
        )

        private val NATIVE_DIR_ABI_ALIASES = mapOf(
            "arm64" to ABI_ARM64_V8A,
            "arm64-v8a" to ABI_ARM64_V8A,
            "armeabi" to "armeabi-v7a",
            "armeabi-v7a" to "armeabi-v7a",
            "x86" to "x86",
            "x86_64" to "x86_64",
        )

        internal fun selectRuntimeAbi(
            nativeLibraryDir: String?,
            supportedAbis: List<String>,
        ): String {
            val selectedDirAbi = nativeLibraryDir
                ?.let { File(it).name }
                ?.let { NATIVE_DIR_ABI_ALIASES[it] ?: it }
                ?.takeIf { it.isNotBlank() && it != "lib" }
            if (selectedDirAbi != null) return selectedDirAbi

            return supportedAbis
                .firstOrNull { it.isNotBlank() }
                ?: UNKNOWN_ABI
        }

        private fun resolveRuntimeProfile(abi: String): RuntimeModelProfile? =
            MODEL_PROFILES[abi]

        /**
         * Files that must be present for the model to be considered ready.
         * Param shards are checked dynamically; these are the invariant metadata files.
         */
        private val REQUIRED_FILES = listOf(
            "mlc-chat-config.json",
            "ndarray-cache.json",
            "tokenizer.json",
        )

        /**
         * Object files that must be present after extracting [TAR_ASSET_NAME].
         * Used to verify extraction was complete before writing the sentinel.
         */
        private val REQUIRED_LIB_FILES = listOf("lib0.o", "llama_q4f16_0_devc.o")

        private const val BUFFER_SIZE = 64 * 1024
    }
}
