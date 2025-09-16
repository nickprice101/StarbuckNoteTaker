package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import com.getkeepsafe.relinker.ReLinker
import java.util.concurrent.atomic.AtomicBoolean

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

