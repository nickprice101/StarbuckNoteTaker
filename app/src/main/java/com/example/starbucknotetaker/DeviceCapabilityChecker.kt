package com.example.starbucknotetaker

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Checks whether the current device has sufficient hardware to run the on-device
 * LLM (Llama 3.2 3B Instruct, q4f16_0 quantisation).
 *
 * The model requires approximately 1.8 GB for weights plus runtime overhead.
 * A minimum of [MIN_RAM_BYTES] (4 GB) total physical RAM is required to run the
 * model safely alongside the OS and other foreground apps.  Devices below this
 * threshold will have AI features disabled at runtime so the app remains stable.
 */
object DeviceCapabilityChecker {

    private const val TAG = "DeviceCapabilityChecker"

    /** Minimum total physical RAM (bytes) required to run the on-device LLM. */
    const val MIN_RAM_BYTES = 4L * 1024L * 1024L * 1024L  // 4 GB

    /**
     * Returns `true` when the device has enough RAM to run the on-device AI model.
     *
     * Uses [ActivityManager.getMemoryInfo] which reports total physical RAM,
     * independent of how much is currently free.
     */
    fun isAiCapable(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val capable = info.totalMem >= MIN_RAM_BYTES
        Log.i(
            TAG,
            "Total RAM: ${info.totalMem / (1024 * 1024)} MB — AI capable: $capable" +
                " (minimum ${MIN_RAM_BYTES / (1024 * 1024 * 1024)} GB required)",
        )
        return capable
    }
}
