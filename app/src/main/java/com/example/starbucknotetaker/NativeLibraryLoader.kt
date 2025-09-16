package com.example.starbucknotetaker

import android.content.Context
import android.os.Build
import android.util.Log
import com.getkeepsafe.relinker.ReLinker
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Utility responsible for loading native libraries that ship with the app.
 *
 * The stock [System.loadLibrary] call occasionally fails on some devices when
 * Play installs split APKs without extracting the `.so` files to the app's
 * native library directory. The ReLinker fallback extracts the library on the
 * fly so we always have a working copy available.
 */
object NativeLibraryLoader {

    private val penguinLoaded = AtomicBoolean(false)

    /** Ensures the SentencePiece JNI bridge is available in the current process. */
    fun ensurePenguin(context: Context): Boolean {
        if (penguinLoaded.get()) return true
        if (loadLibrary(context, "penguin")) {
            penguinLoaded.set(true)
            return true
        }
        // Fall back to the original DJL name in case the repackaging step
        // failed and the dependency only contributed libdjl_tokenizer.so.
        if (loadLibrary(context, "djl_tokenizer")) {
            penguinLoaded.set(true)
            return true
        }
        return false
    }

    private fun loadLibrary(context: Context, name: String): Boolean {
        return try {
            System.loadLibrary(name)
            true
        } catch (first: UnsatisfiedLinkError) {
            if (loadLibraryFromApk(context, name)) {
                true
            } else {
                try {
                    ReLinker.loadLibrary(context, name)
                    true
                } catch (second: UnsatisfiedLinkError) {
                    Log.e("NativeLibraryLoader", "Failed to load native library $name", second)
                    false
                }
            }
        }
    }

    private fun loadLibraryFromApk(context: Context, name: String): Boolean {
        val extracted = try {
            extractLibraryFromApk(context, name)
        } catch (t: Throwable) {
            Log.e("NativeLibraryLoader", "Failed extracting native library $name", t)
            null
        }
        if (extracted == null) return false
        return try {
            System.load(extracted.absolutePath)
            true
        } catch (t: Throwable) {
            Log.e("NativeLibraryLoader", "Failed to load native library $name from extracted copy", t)
            extracted.delete()
            false
        }
    }

    @Throws(IOException::class)
    private fun extractLibraryFromApk(context: Context, name: String): File? {
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

