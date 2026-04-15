package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import com.getkeepsafe.relinker.ReLinker

/**
 * Thin Kotlin wrapper around the llama_jni native library.
 *
 * The library is loaded once via [ReLinker] so it survives multi-ABI extraction
 * edge-cases.  All callers should check [isAvailable] before attempting JNI
 * calls; when the native library is absent the inference layer falls back to
 * the on-device rule-based summariser.
 *
 * The native side returns [STUB_RESPONSE_MARKER] when compiled without the
 * real llama.cpp sources, allowing the Kotlin layer to detect the stub and
 * route through the heuristic fallback without any changes.
 */
object LlamaJni {

    /** Marker returned by the native stub when llama.cpp is not compiled in. */
    const val STUB_RESPONSE_MARKER = "__LLAMA_STUB__"

    private const val TAG = "LlamaJni"
    private const val LIB_NAME = "llama_jni"

    @Volatile
    private var loaded = false

    /**
     * Attempts to load the native library.  Safe to call multiple times.
     *
     * @return `true` if the library loaded successfully and JNI calls can proceed.
     */
    fun load(context: Context): Boolean {
        if (loaded) return true
        return try {
            ReLinker.loadLibrary(context.applicationContext, LIB_NAME)
            loaded = true
            Log.i(TAG, "Native library $LIB_NAME loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library $LIB_NAME not available — AI will use rule-based fallback", e)
            false
        }
    }

    /** @return whether the native library has been loaded successfully. */
    fun isAvailable(): Boolean = loaded

    // ------------------------------------------------------------------
    // JNI declarations
    // ------------------------------------------------------------------

    /**
     * Initialises a llama.cpp model context.
     *
     * @param modelPath Absolute path to the `.gguf` model file on the device.
     * @param nCtx      Context window size (tokens).
     * @param nThreads  CPU threads to use for inference.
     * @return Opaque handle (≠ 0) on success, or 0 on failure.
     */
    external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Long

    /**
     * Generates text given a prompt, streaming individual tokens via [callback].
     *
     * @param handle    Opaque handle returned by [nativeInit].
     * @param prompt    Full formatted prompt string (incl. chat template tokens).
     * @param maxTokens Maximum number of tokens to generate.
     * @param callback  Called for each generated token; return `false` to abort.
     * @return The complete generated text, or [STUB_RESPONSE_MARKER] in stub mode.
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        callback: TokenCallback,
    ): String

    /**
     * Releases a llama.cpp model context and frees all associated memory.
     *
     * @param handle Opaque handle returned by [nativeInit].
     */
    external fun nativeRelease(handle: Long)

    // ------------------------------------------------------------------
    // Callback interface
    // ------------------------------------------------------------------

    /** Called by native code for each generated token during streaming. */
    fun interface TokenCallback {
        /**
         * @param token A single generated token piece (may be a sub-word).
         * @return `true` to continue generation; `false` to abort.
         */
        fun onToken(token: String): Boolean
    }
}
