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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle of the ABI-optimized Llama MLC model on device.
 *
 * The model weights (~2 GB) are never bundled in the APK.  They are downloaded from
 * HuggingFace on first use and stored in
 * an ABI-specific directory under `filesDir/models/`. ARM64 phones use the
 * full Llama 3.2 3B q4f16 OpenCL profile. Resource-constrained x86_64 Android
 * emulators use a portable Llama 3.2 1B q4f32 CPU/AVX2 compatibility profile;
 * emulator correctness never depends on access to the host's discrete GPU.
 *
 * The MLC model directory contains:
 *   - `mlc-chat-config.json`   — model/tokenizer configuration
 *   - `ndarray-cache.json`     — weight-shard manifest
 *   - `tokenizer.json`         — vocabulary / BPE data
 *   - `tokenizer_config.json`  — tokenizer hyper-parameters
 *   - `params_shard_*.bin`     — actual model weights (many shards)
 *
 * **Model library flow:**
 * The compiled model library starts as [TAR_ASSET_NAME], bundled inside the APK's
 * `assets/` directory. The Gradle task `buildModelLibSo` links the object files
 * from that archive into a native `.so` under `jniLibs/<abi>/`.
 *
 * At runtime [LlamaEngine] loads the linked `.so` with `System.load(path)` and
 * passes [MODEL_LIB_SYSTEM_HANDLE] to [ai.mlc.mlcllm.MLCEngine.reload]. The
 * [extractModelLibIfNeeded] helper is retained for validating or inspecting the
 * archive contents, but it is not part of the active inference load path.
 *
 * Usage:
 * ```
 * val manager = LlamaModelManager(context)
 * manager.modelStatus.collect { status -> /* update UI */ }
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

    fun getRuntimeMlcDeviceType(): String? = runtimeProfile?.mlcDeviceType

    fun getRuntimeMlcEngineMode(): String? = runtimeProfile?.mlcEngineMode

    fun getRuntimeModelSoFilename(): String =
        runtimeProfile?.modelSoFilename ?: MODEL_SO_FILENAME

    fun getRuntimeModelLibSystemHandle(): String =
        runtimeProfile?.modelLibSystemHandle ?: MODEL_LIB_SYSTEM_HANDLE

    private val modelDir: File
        get() = modelDirFor(runtimeProfile)

    init {
        _modelStatus.value = computeCurrentStatus()
    }

    /**
     * Returns the path to the model weights directory if all required files and
     * every weight shard listed by `ndarray-cache.json` are present.
     *
     * Checking only the metadata JSON files, or even the presence of a single shard, is
     * insufficient: a partial download would otherwise pass this guard and fail later inside
     * MLC when it tries to open a missing `params_shard_*.bin`.
     */
    fun getModelPath(): String? {
        val profile = runtimeProfile ?: return null
        return getModelPath(profile)
    }

    private fun getModelPath(profile: RuntimeModelProfile): String? {
        val dir = modelDirFor(profile)
        val metaPresent = REQUIRED_FILES.all { name -> File(dir, name).exists() }
        if (!metaPresent) return null
        val shards = requiredWeightShards(dir) ?: return null
        if (shards.isEmpty()) return null
        val allShardsPresent = shards.all { (dataPath, expectedBytes) ->
            val shard = File(dir, dataPath)
            shard.isFile && (expectedBytes <= 0L || shard.length() == expectedBytes)
        }
        return if (allShardsPresent) dir.absolutePath else null
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
                    if (targetFile.exists() && (expectedSize <= 0L || targetFile.length() == expectedSize)) {
                        downloadedBytes += if (expectedSize > 0L) expectedSize else targetFile.length()
                        Log.d(TAG, "Skipping already-downloaded $fileName")
                        continue
                    }

                    val url = profile.fileUrl(fileName)
                    val ok = downloadFile(client, url, targetFile) { fileBytes ->
                        val cumulativeBytes = downloadedBytes + fileBytes
                        val pct = if (totalBytes > 0) {
                            (cumulativeBytes * 100L / totalBytes).toInt()
                        } else 0
                        _modelStatus.value = ModelStatus.Downloading(
                            pct,
                            label,
                            cumulativeBytes,
                            totalBytes,
                            profile.abi,
                        )
                        onProgress(pct)
                    }
                    if (!ok) {
                        _modelStatus.value = ModelStatus.Error("Failed to download $fileName")
                        return@withContext false
                    }
                    downloadedBytes += if (expectedSize > 0L) expectedSize else targetFile.length()
                }

                val verifiedPath = getModelPath(profile)
                if (verifiedPath == null) {
                    _modelStatus.value = ModelStatus.Error("Downloaded model is incomplete")
                    Log.e(TAG, "Downloaded model is incomplete after fetching all listed files")
                    return@withContext false
                }

                val sizeBytes = dest.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                _modelStatus.value = ModelStatus.Present(verifiedPath, sizeBytes, profile.abi)
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
     * for diagnostics or archive validation.
     *
     * The active inference path uses the Gradle-linked `.so` in `jniLibs/<abi>/`
     * and [MODEL_LIB_SYSTEM_HANDLE], not this extracted directory. Extraction is
     * skipped when the sentinel file `[MODEL_LIB_EXTRACT_SUBDIR]/.extracted`
     * already exists and the expected object files are still present.
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
        if (sentinel.exists() && hasRequiredModelObjects(libDir, profile.abi)) {
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
            if (!hasRequiredModelObjects(libDir, profile.abi)) {
                Log.e(TAG, "Model lib extraction incomplete")
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
        val tvmRuntimeSo = java.io.File(nativeLibDir, TVM_RUNTIME_SO_FILENAME)
        sb.appendLine("TVM runtime .so: ${tvmRuntimeSo.absolutePath} (exists=${tvmRuntimeSo.exists()}," +
            " size=${if (tvmRuntimeSo.exists()) "%.1f MB".format(tvmRuntimeSo.length() / (1024.0 * 1024.0)) else "n/a"})")
        val modelSo = java.io.File(nativeLibDir, getRuntimeModelSoFilename())
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

    private fun hasRequiredModelObjects(libDir: File, abi: String): Boolean {
        val files = libDir.listFiles()?.filter { it.isFile } ?: return false
        val hasLib0 = files.any { it.name == "lib0.o" }
        val hasModelObject = files.any { it.name != "lib0.o" && it.name.endsWith(".o") }
        return hasLib0 && (abi == ABI_X86_64 || hasModelObject)
    }

    private fun requiredWeightShards(dir: File): List<Pair<String, Long>>? {
        val manifest = File(dir, "ndarray-cache.json")
        if (!manifest.isFile) return null
        return try {
            val records = JSONObject(manifest.readText()).optJSONArray("records") ?: return emptyList()
            buildList {
                for (i in 0 until records.length()) {
                    val record = records.optJSONObject(i) ?: continue
                    val dataPath = record.optString("dataPath").trim()
                    if (dataPath.isNotEmpty()) {
                        add(dataPath to record.optLong("nbytes", -1L))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse weight-shard manifest at ${manifest.absolutePath}", e)
            null
        }
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
        onFileBytes: (Long) -> Unit,
    ): Boolean {
        val tmp = File(dest.parent, "${dest.name}.tmp")
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attemptIndex ->
            val attempt = attemptIndex + 1
            var fileBytes = 0L
            try {
                tmp.delete()
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val retry = shouldRetryDownload(response.code) && attempt < MAX_DOWNLOAD_ATTEMPTS
                        val level = if (retry) Log.WARN else Log.ERROR
                        Log.println(level, TAG, "HTTP ${response.code} for $url (attempt $attempt/$MAX_DOWNLOAD_ATTEMPTS)")
                        if (retry) sleepBeforeRetry(attempt)
                        return@repeat
                    }
                    val body = response.body ?: return false
                    FileOutputStream(tmp).use { out ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (inp.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                                fileBytes += read.toLong()
                                onFileBytes(fileBytes)
                            }
                        }
                    }
                }
                if (tmp.renameTo(dest)) {
                    return true
                }
                Log.w(TAG, "Could not rename ${tmp.absolutePath} to ${dest.absolutePath}")
            } catch (e: Exception) {
                val retry = attempt < MAX_DOWNLOAD_ATTEMPTS
                val level = if (retry) Log.WARN else Log.ERROR
                Log.println(level, TAG, "Download failed for $url (attempt $attempt/$MAX_DOWNLOAD_ATTEMPTS): ${e.message}")
                if (retry) sleepBeforeRetry(attempt)
            }
            tmp.delete()
        }
        return false
    }

    private fun shouldRetryDownload(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 429 || statusCode in 500..599

    private fun sleepBeforeRetry(attempt: Int) {
        try {
            val delayMs = minOf(
                DOWNLOAD_RETRY_MAX_DELAY_MS,
                DOWNLOAD_RETRY_BASE_DELAY_MS * attempt,
            )
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private data class RuntimeModelProfile(
        val abi: String,
        val hfRepoId: String,
        val modelSubdir: String,
        val tarAssetName: String,
        val modelLibExtractSubdir: String,
        val modelSoFilename: String,
        val modelLibSystemHandle: String,
        val mlcDeviceType: String,
        val mlcEngineMode: String,
    ) {
        fun fileUrl(fileName: String): String =
            "https://huggingface.co/$hfRepoId/resolve/main/$fileName"
    }

    companion object {
        private const val TAG = "LlamaModelManager"
        const val UNKNOWN_ABI = "unknown"

        /**
         * Asset file name of the `.tar` used to build the compiled MLC model library.
         * The archive is packaged in `assets/` and linked into a `.so` by Gradle;
         * [extractModelLibIfNeeded] can also unpack it for diagnostics.
         */
        const val TAR_ASSET_NAME = "Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar"
        const val TAR_ASSET_NAME_X86_64 = "Llama-3.2-1B-Instruct-q4f32_1-MLC-android-x86_64.tar"

        /**
         * Sub-directory inside `filesDir` where the model library `.tar` can be
         * extracted for diagnostics or archive validation.
         */
        const val MODEL_LIB_EXTRACT_SUBDIR = "lib/Llama-3.2-3B-Instruct-q4f16_0-MLC-android"
        const val MODEL_LIB_EXTRACT_SUBDIR_X86_64 = "lib/Llama-3.2-1B-Instruct-q4f32_1-MLC-android-x86_64"

        /** ARM64/OpenCL HuggingFace model repository identifier. */
        const val HF_REPO_ID = "mlc-ai/Llama-3.2-3B-Instruct-q4f16_0-MLC"
        const val HF_REPO_ID_X86_64 = "mlc-ai/Llama-3.2-1B-Instruct-q4f32_1-MLC"

        /**
         * Sub-directory inside `filesDir` where the model weights live.
         * This is also the value passed to [ai.mlc.mlcllm.MLCEngine.reload] as `modelPath`.
         */
        const val MODEL_SUBDIR = "models/Llama-3.2-3B-Instruct-q4f16_0-MLC"
        const val MODEL_SUBDIR_X86_64 = "models/Llama-3.2-1B-Instruct-q4f32_1-MLC"

        /**
         * The Android library name for the compiled model kernel library.
         *
         * This is the bare name (without the `lib` prefix or `.so` suffix) used to
         * derive [MODEL_SO_FILENAME] and [MODEL_LIB_SYSTEM_HANDLE].
         */
        const val MODEL_LIB_NAME = "Llama-3.2-3B-Instruct-q4f16_0-MLC"
        const val MODEL_LIB_NAME_X86_64 = "Llama-3.2-1B-Instruct-q4f32_1-MLC"

        /**
         * Name used by TVM's system-lib registry.
         *
         * MLC registers the compiled Android system library under the compiler
         * module prefix embedded in lib0.o, not under the HuggingFace repo-style
         * name used for the .so filename. The linked library exposes symbols such
         * as `llama_q4f16_0___tvm_ffi__library_ctx`, so the system-lib lookup name
         * must be `llama_q4f16_0`.
         */
        const val MODEL_LIB_SYSTEM_NAME = "llama_q4f16_0"
        const val MODEL_LIB_SYSTEM_NAME_X86_64 = "llama_1b_q4f32_1"

        /**
         * The filename of the compiled model kernel library bundled in the APK's
         * `jniLibs/<abi>/` directory.
         *
         * Android extracts this file to {@code applicationInfo.nativeLibraryDir} at install time.
         * The library is loaded via {@code System.load(path)} so that the model's TVM FFI
         * system-library metadata is registered with the packed TVM runtime.
         *
         * The library is built by the Gradle task `buildModelLibSo`, which links the
         * `.o` files from [TAR_ASSET_NAME] into a single `.so` that works with the
         * source-built, TVM FFI-capable `libtvm4j_runtime_packed.so`.
         */
        const val MODEL_SO_FILENAME = "lib$MODEL_LIB_NAME.so"
        const val MODEL_SO_FILENAME_X86_64 = "lib$MODEL_LIB_NAME_X86_64.so"
        const val TVM_RUNTIME_SO_FILENAME = "libtvm4j_runtime_packed.so"

        /**
         * The system-library handle passed to [ai.mlc.mlcllm.MLCEngine.reload] as `modelLib`.
         *
         * After the model kernel library has been loaded via {@code System.load} (which registers
         * all kernel functions with TVM's system-lib registry), MLC-LLM must be told to access
         * those functions through the system-lib mechanism rather than via TVM's file loader.
         * Passing the `system://` prefix instructs MLC-LLM to call `ffi.SystemLib()` and
         * retrieve the pre-registered module.
         */
        const val MODEL_LIB_SYSTEM_HANDLE = "system://$MODEL_LIB_SYSTEM_NAME"
        const val MODEL_LIB_SYSTEM_HANDLE_X86_64 = "system://$MODEL_LIB_SYSTEM_NAME_X86_64"

        /** Human-readable approximate download size shown in the Settings UI. */
        const val MODEL_SIZE_LABEL = "~2.0 GB"

        /** Human-readable model name used in request metadata. */
        const val MODEL_DISPLAY_NAME = "Llama-3.2-3B-Instruct"

        private const val ABI_ARM64_V8A = "arm64-v8a"
        private const val ABI_X86_64 = "x86_64"
        private const val MAX_DOWNLOAD_ATTEMPTS = 12
        private const val DOWNLOAD_RETRY_BASE_DELAY_MS = 1_000L
        private const val DOWNLOAD_RETRY_MAX_DELAY_MS = 10_000L
        val SUPPORTED_MODEL_ABIS: List<String> = listOf(ABI_ARM64_V8A, ABI_X86_64)

        private val MODEL_PROFILES: Map<String, RuntimeModelProfile> = mapOf(
            ABI_ARM64_V8A to RuntimeModelProfile(
                abi = ABI_ARM64_V8A,
                hfRepoId = HF_REPO_ID,
                modelSubdir = MODEL_SUBDIR,
                tarAssetName = TAR_ASSET_NAME,
                modelLibExtractSubdir = MODEL_LIB_EXTRACT_SUBDIR,
                modelSoFilename = MODEL_SO_FILENAME,
                modelLibSystemHandle = MODEL_LIB_SYSTEM_HANDLE,
                mlcDeviceType = "opencl",
                mlcEngineMode = "interactive",
            ),
            ABI_X86_64 to RuntimeModelProfile(
                abi = ABI_X86_64,
                hfRepoId = HF_REPO_ID_X86_64,
                modelSubdir = MODEL_SUBDIR_X86_64,
                tarAssetName = TAR_ASSET_NAME_X86_64,
                modelLibExtractSubdir = MODEL_LIB_EXTRACT_SUBDIR_X86_64,
                modelSoFilename = MODEL_SO_FILENAME_X86_64,
                modelLibSystemHandle = MODEL_LIB_SYSTEM_HANDLE_X86_64,
                mlcDeviceType = "cpu",
                mlcEngineMode = "interactive",
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
        private const val BUFFER_SIZE = 64 * 1024
    }
}
