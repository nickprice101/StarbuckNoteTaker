package com.example.starbucknotetaker

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Application entry point used to perform lightweight service warm up for
 * Qualcomm GPU drivers.
 */
class StarbuckNoteTakerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val processName = resolveProcessName()
        if (processName == null || processName == packageName) {
            QspmServiceInitializer.warmUp(this)
        } else {
            Log.d(TAG, "Skipping main process initialisation for $processName")
        }
    }

    private fun resolveProcessName(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            currentProcessNameLegacy(this, Process.myPid())
        }
    }

    companion object {
        private const val TAG = "StarbuckApp"

        private fun currentProcessNameLegacy(context: Context, pid: Int): String? {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            return runCatching { manager.runningAppProcesses }
                .getOrNull()
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
    }
}
