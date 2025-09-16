package com.example.starbucknotetaker

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import com.getkeepsafe.relinker.ReLinker
import kotlin.jvm.Volatile

/**
 * Utility responsible for loading native libraries that ship with the app.
 *
 * The stock [System.loadLibrary] call occasionally fails on some devices when
 * Play installs split APKs without extracting the `.so` files to the app's
 * native library directory. The ReLinker fallback extracts the library on the
 * fly so we always have a working copy available.
 */
object NativeLibraryLoader {

    private const val TAG = "NativeLibraryLoader"

    private data class ExtractionAttempt(
        val apkPath: String,
        val abi: String,
        val destination: File
    )

    private data class LoadFromApkResult(
        val success: Boolean,
        val extractedFile: File?,
        val attempts: List<ExtractionAttempt>,
        val failureReason: Throwable?
    )

    private val penguinLoaded = AtomicBoolean(false)
    @Volatile
    private var loadLibraryOverride: ((String) -> Unit)? = null

    private fun loadNativeLibrary(name: String) {
        val loader = loadLibraryOverride
        if (loader != null) {
            loader(name)
        } else {
            System.loadLibrary(name)
        }
    }

    /** Ensures the SentencePiece JNI bridge is available in the current process. */
    fun ensurePenguin(context: Context): Boolean {
        if (penguinLoaded.get()) return true
        var loaded = loadLibrary(context, "penguin")
        if (!loaded) {
            Log.w(
                TAG,
                "Falling back to loading djl_tokenizer after penguin failed; packaging may be missing libpenguin.so"
            )
            loaded = try {
                loadNativeLibrary("djl_tokenizer")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load fallback native library djl_tokenizer", t)
                false
            }
        }
        if (loaded) {
            penguinLoaded.set(true)
        }
        return loaded
    }

    /** Overrides the system loadLibrary call for tests. */
    internal fun setLoadLibraryOverrideForTesting(loader: ((String) -> Unit)?) {
        loadLibraryOverride = loader
    }

    private fun loadLibrary(context: Context, name: String): Boolean {
        return try {
            loadNativeLibrary(name)
            true
        } catch (first: UnsatisfiedLinkError) {
            Log.w(TAG, "System.loadLibrary failed for $name: ${first.message}")
            val apkResult = loadLibraryFromApk(context, name)
            apkResult.attempts.forEach { attempt ->
                Log.d(
                    TAG,
                    "[loadLibrary] Tested APK ${attempt.apkPath} for ABI ${attempt.abi} -> ${attempt.destination.absolutePath}"
                )
            }
            if (apkResult.success) {
                true
            } else {
                val extractedPath = apkResult.extractedFile?.absolutePath
                val apkFailureMessage = apkResult.failureReason?.message ?: "unknown"
                Log.w(
                    TAG,
                    "loadLibraryFromApk failed for $name: $apkFailureMessage (extractedPath=$extractedPath)"
                )
                try {
                    ReLinker.loadLibrary(context, name)
                    true
                } catch (second: UnsatisfiedLinkError) {
                    Log.e(
                        TAG,
                        "Failed to load native library $name. Final failure: ${second.message}; " +
                            "system failure: ${first.message}; extractedPath=$extractedPath",
                        second
                    )
                    false
                }
            }
        }
    }

    internal fun findLibraryOnDisk(context: Context, name: String): File? {
        val libFileName = System.mapLibraryName(name)
        val searchRoots = mutableListOf<File>()
        context.applicationInfo.nativeLibraryDir?.let { searchRoots.add(File(it)) }
        searchRoots.add(File(context.noBackupFilesDir, "native/$name"))
        searchRoots.add(File(context.filesDir, "lib"))
        context.codeCacheDir?.let { searchRoots.add(File(it, "lib")) }

        searchRoots.forEach { root ->
            val found = locateInDirectory(root, libFileName)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun locateInDirectory(root: File, libFileName: String): File? {
        if (!root.exists()) return null
        if (root.isFile) {
            return if (root.name == libFileName) root else null
        }

        val queue: ArrayDeque<File> = ArrayDeque()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!current.exists()) continue
            if (current.isFile) {
                if (current.name == libFileName) {
                    return current
                }
            } else {
                current.listFiles()?.forEach { child ->
                    if (child.isFile) {
                        if (child.name == libFileName) {
                            return child
                        }
                    } else if (child.isDirectory) {
                        queue.add(child)
                    }
                }
            }
        }
        return null
    }

    private fun loadLibraryFromApk(context: Context, name: String): LoadFromApkResult {
        val attempts = mutableListOf<ExtractionAttempt>()
        val extracted = try {
            extractLibraryFromApk(context, name, attempts)
        } catch (t: Throwable) {
            attempts.forEach { attempt ->
                Log.d(
                    TAG,
                    "[loadLibraryFromApk] Tested APK ${attempt.apkPath} for ABI ${attempt.abi} -> ${attempt.destination.absolutePath}"
                )
            }
            Log.e(TAG, "Failed extracting native library $name", t)
            Log.w(TAG, "[loadLibraryFromApk] Final failure reason: ${t.message}; extractedPath=null")
            return LoadFromApkResult(false, null, attempts, t)
        }
        attempts.forEach { attempt ->
            Log.d(
                TAG,
                "[loadLibraryFromApk] Tested APK ${attempt.apkPath} for ABI ${attempt.abi} -> ${attempt.destination.absolutePath}"
            )
        }
        if (extracted == null) {
            val failure = IllegalStateException("Library $name not found in tested APKs")
            Log.w(TAG, "[loadLibraryFromApk] Final failure reason: ${failure.message}; extractedPath=null")
            return LoadFromApkResult(false, null, attempts, failure)
        }
        return try {
            System.load(extracted.absolutePath)
            Log.i(TAG, "Successfully loaded native library $name from ${extracted.absolutePath}")
            LoadFromApkResult(true, extracted, attempts, null)
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Failed to load native library $name from extracted copy at ${extracted.absolutePath}",
                t
            )
            extracted.delete()
            Log.w(TAG, "[loadLibraryFromApk] Final failure reason: ${t.message}; extractedPath=${extracted.absolutePath}")
            LoadFromApkResult(false, extracted, attempts, t)
        }
    }

    @Throws(IOException::class)
    private fun extractLibraryFromApk(
        context: Context,
        name: String,
        attempts: MutableList<ExtractionAttempt>
    ): File? {
        val libFileName = "lib${name}.so"
        val appInfo = context.applicationInfo
        val apkPaths = buildList {
            appInfo.sourceDir?.let { add(it) }
            appInfo.splitSourceDirs?.let { addAll(it) }
        }.distinct()
        if (apkPaths.isEmpty()) return null

        val supportedAbis = Build.SUPPORTED_ABIS
        if (supportedAbis.isEmpty()) return null
        for (apkPath in apkPaths) {
            ZipFile(apkPath).use { zip ->
                for (abi in supportedAbis) {
                    val entryName = "lib/${abi}/${libFileName}"
                    val entry = zip.getEntry(entryName) ?: continue
                    val libDir = File(context.noBackupFilesDir, "native/${name}/${abi}")
                    if (!libDir.exists() && !libDir.mkdirs() && !libDir.exists()) continue
                    val dest = File(libDir, libFileName)
                    attempts.add(ExtractionAttempt(apkPath, abi, dest))
                    val expectedSize = entry.size
                    if (dest.exists() && expectedSize > 0 && dest.length() == expectedSize) {
                        return dest
                    }
                    zip.getInputStream(entry).use { ins ->
                        FileOutputStream(dest).use { out ->
                            ins.copyTo(out)
                        }
                    }
                    dest.setReadable(true, false)
                    dest.setExecutable(true, false)
                    dest.setWritable(true, true)
                    return dest
                }
            }
        }
        return null
    }
}

