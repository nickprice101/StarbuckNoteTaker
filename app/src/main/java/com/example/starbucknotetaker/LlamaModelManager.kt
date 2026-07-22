package com.example.starbucknotetaker

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/**
 * Downloads and validates the single-file LiteRT-LM model used by the app.
 *
 * The model is a portable mixed-INT4 Qwen3 0.6B bundle. The same `.litertlm`
 * file runs on ARM64 phones and x86_64 emulators, so model installation no
 * longer depends on an ABI-specific TVM runtime or a separately compiled model
 * kernel library.
 */
class LlamaModelManager(private val context: Context) {

    sealed class ModelStatus {
        object Missing : ModelStatus()
        data class Present(
            val path: String,
            val sizeBytes: Long = 0L,
            val abi: String = UNKNOWN_ABI,
        ) : ModelStatus()
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

    private val modelDir: File
        get() = File(context.filesDir, MODEL_SUBDIR)

    private val modelFile: File
        get() = File(modelDir, MODEL_FILENAME)

    private val checksumFile: File
        get() = File(modelDir, CHECKSUM_SENTINEL_FILENAME)

    init {
        _modelStatus.value = computeCurrentStatus()
    }

    /** Returns the validated `.litertlm` model path, or `null` when unavailable. */
    fun getModelPath(): String? {
        if (runtimeAbi !in SUPPORTED_MODEL_ABIS) return null
        val file = modelFile
        val checksum = checksumFile
        return if (
            file.isFile &&
            file.length() == MODEL_SIZE_BYTES &&
            checksum.isFile &&
            checksum.readText().trim().equals(MODEL_SHA256, ignoreCase = true)
        ) {
            file.absolutePath
        } else {
            null
        }
    }

    fun isModelPresent(): Boolean = getModelPath() != null

    fun refreshStatus() {
        _modelStatus.value = computeCurrentStatus()
    }

    /**
     * Downloads the pinned LiteRT-LM bundle with resumable HTTP range requests.
     * The completed file is accepted only after its byte size and SHA-256 match
     * the immutable Hugging Face revision below.
     */
    suspend fun downloadModel(onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            if (runtimeAbi !in SUPPORTED_MODEL_ABIS) {
                _modelStatus.value = unsupportedStatus()
                return@withContext false
            }

            val destination = modelFile
            val partial = File(modelDir, "$MODEL_FILENAME.part")
            modelDir.mkdirs()

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            try {
                _modelStatus.value = downloadingStatus(partial.length(), "Preparing download…")
                val downloaded = downloadWithResume(client, MODEL_URL, partial, onProgress)
                if (!downloaded) {
                    _modelStatus.value = ModelStatus.Error(
                        "Could not download the LiteRT-LM model. Check the connection and retry.",
                    )
                    return@withContext false
                }

                _modelStatus.value = downloadingStatus(MODEL_SIZE_BYTES, "Verifying model…")
                val actualSha256 = sha256(partial)
                if (!actualSha256.equals(MODEL_SHA256, ignoreCase = true)) {
                    partial.delete()
                    _modelStatus.value = ModelStatus.Error(
                        "The downloaded model failed its integrity check. Please retry.",
                    )
                    return@withContext false
                }

                destination.delete()
                check(partial.renameTo(destination)) {
                    "Could not install the downloaded model file."
                }
                checksumFile.writeText(MODEL_SHA256)

                _modelStatus.value = ModelStatus.Present(
                    path = destination.absolutePath,
                    sizeBytes = destination.length(),
                    abi = runtimeAbi,
                )
                onProgress(100)
                Log.i(TAG, "LiteRT-LM model installed at ${destination.absolutePath}")
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "LiteRT-LM model download failed", failure)
                _modelStatus.value = ModelStatus.Error(
                    failure.message ?: "Model download failed",
                )
                false
            }
        }

    fun deleteModel() {
        LlamaEngineProvider.closeNow()
        modelDir.deleteRecursively()
        LEGACY_MODEL_SUBDIRS.forEach { subdir ->
            File(context.filesDir, subdir).deleteRecursively()
        }
        _modelStatus.value = computeCurrentStatus()
    }

    fun modelSizeGb(): Float? = modelFile
        .takeIf { getModelPath() != null }
        ?.length()
        ?.toFloat()
        ?.div(1024 * 1024 * 1024)

    fun debugModelDirInfo(): String = buildString {
        appendLine("Runtime ABI: $runtimeAbi (supported=${runtimeAbi in SUPPORTED_MODEL_ABIS})")
        appendLine("LiteRT-LM model: ${modelFile.absolutePath}")
        appendLine("  exists=${modelFile.isFile} bytes=${modelFile.takeIf(File::isFile)?.length() ?: 0L}")
        append("  checksum sentinel=${checksumFile.isFile}")
    }

    private fun computeCurrentStatus(): ModelStatus {
        if (runtimeAbi !in SUPPORTED_MODEL_ABIS) return unsupportedStatus()
        val path = getModelPath() ?: return ModelStatus.Missing
        return ModelStatus.Present(path, modelFile.length(), runtimeAbi)
    }

    private fun unsupportedStatus(): ModelStatus.Unsupported = ModelStatus.Unsupported(
        abi = runtimeAbi,
        supportedAbis = SUPPORTED_MODEL_ABIS,
        message = "LiteRT-LM is packaged for ${SUPPORTED_MODEL_ABIS.joinToString()} but this " +
            "installation is running '$runtimeAbi'.",
    )

    private fun downloadingStatus(bytes: Long, label: String): ModelStatus.Downloading {
        val boundedBytes = bytes.coerceIn(0L, MODEL_SIZE_BYTES)
        return ModelStatus.Downloading(
            progressPercent = downloadProgressPercent(boundedBytes),
            label = label,
            downloadedBytes = boundedBytes,
            totalBytes = MODEL_SIZE_BYTES,
            abi = runtimeAbi,
        )
    }

    private suspend fun downloadWithResume(
        client: OkHttpClient,
        url: String,
        destination: File,
        onProgress: (Int) -> Unit,
    ): Boolean {
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attemptIndex ->
            coroutineContext.ensureActive()
            if (destination.length() == MODEL_SIZE_BYTES) return true
            if (destination.length() > MODEL_SIZE_BYTES) destination.delete()

            val existingBytes = destination.length()
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (existingBytes > 0L) header("Range", "bytes=$existingBytes-")
                }
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Model download HTTP ${response.code} on attempt ${attemptIndex + 1}")
                        return@use
                    }
                    val append = existingBytes > 0L && response.code == 206
                    var downloadedBytes = if (append) existingBytes else 0L
                    FileOutputStream(destination, append).use { output ->
                        val body = response.body ?: error("Model download returned no content")
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val percent = (downloadedBytes * 100L / MODEL_SIZE_BYTES)
                                    .toInt()
                                    .coerceIn(0, 100)
                                _modelStatus.value = downloadingStatus(
                                    downloadedBytes,
                                    "Downloading $MODEL_DISPLAY_NAME…",
                                )
                                onProgress(percent)
                            }
                        }
                    }
                }
                if (destination.length() == MODEL_SIZE_BYTES) return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Model download attempt ${attemptIndex + 1} failed", failure)
            }

            if (attemptIndex + 1 < MAX_DOWNLOAD_ATTEMPTS) {
                kotlinx.coroutines.delay(RETRY_BASE_DELAY_MS * (attemptIndex + 1))
            }
        }
        return false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(DOWNLOAD_BUFFER_SIZE).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val TAG = "LlamaModelManager"
        const val UNKNOWN_ABI = "unknown"

        const val HF_REPO_ID = "litert-community/Qwen3-0.6B"
        const val HF_REVISION = "dd97997951bb15a2a71f539ba17f604707c0b11a"
        const val MODEL_FILENAME = "qwen3_0_6b_mixed_int4.litertlm"
        const val MODEL_SUBDIR = "models/Qwen3-0.6B-LiteRT-LM"
        const val MODEL_SUBDIR_X86_64 = MODEL_SUBDIR
        const val MODEL_DISPLAY_NAME = "Qwen3 0.6B"
        const val MODEL_SIZE_LABEL = "~475 MB"
        const val MODEL_SIZE_BYTES = 497_664_000L
        const val MODEL_SHA256 = "b1baab462f6be49d70eada79d715c2c52cd9ece0cad00bddf6a2c097d23498e9"
        const val MODEL_URL =
            "https://huggingface.co/$HF_REPO_ID/resolve/$HF_REVISION/$MODEL_FILENAME?download=true"

        private const val CHECKSUM_SENTINEL_FILENAME = ".model.sha256"
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        private const val MAX_DOWNLOAD_ATTEMPTS = 5
        private const val RETRY_BASE_DELAY_MS = 1_000L
        private val LEGACY_MODEL_SUBDIRS = listOf(
            "models/Llama-3.2-3B-Instruct-q4f16_0-MLC",
            "models/Llama-3.2-1B-Instruct-q4f32_1-MLC",
        )

        val SUPPORTED_MODEL_ABIS: List<String> = listOf("arm64-v8a", "x86_64")

        private val NATIVE_DIR_ABI_ALIASES = mapOf(
            "arm64" to "arm64-v8a",
            "arm64-v8a" to "arm64-v8a",
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
                ?.let(::File)
                ?.name
                ?.let { NATIVE_DIR_ABI_ALIASES[it] ?: it }
                ?.takeIf { it.isNotBlank() && it != "lib" }
            if (selectedDirAbi != null) return selectedDirAbi
            return supportedAbis.firstOrNull { it.isNotBlank() } ?: UNKNOWN_ABI
        }

        internal fun downloadProgressPercent(
            downloadedBytes: Long,
            totalBytes: Long = MODEL_SIZE_BYTES,
        ): Int {
            if (totalBytes <= 0L) return 0
            return (downloadedBytes.coerceIn(0L, totalBytes) * 100L / totalBytes).toInt()
        }
    }
}
