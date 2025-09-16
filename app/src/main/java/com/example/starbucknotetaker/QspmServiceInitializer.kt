package com.example.starbucknotetaker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proactively spins up [QspmService] so vendor drivers can bind to the AIDL
 * interface even before the UI process runs.
 */
object QspmServiceInitializer {
    private val bindingInProgress = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Attempts to bind to [QspmService] momentarily which forces Android to
     * create the remote process and perform any lazy initialisation.
     */
    fun warmUp(context: Context) {
        val appContext = context.applicationContext
        if (!bindingInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Skipping QSPM warm up; a bind is already in progress")
            return
        }

        lateinit var connection: ServiceConnection
        lateinit var timeoutRunnable: Runnable

        fun releaseBinding() {
            handler.removeCallbacks(timeoutRunnable)
            if (!bindingInProgress.compareAndSet(true, false)) {
                return
            }
            runCatching {
                appContext.unbindService(connection)
            }.onFailure { error ->
                Log.w(TAG, "Failed to tear down QSPM warm up binding", error)
            }
        }

        timeoutRunnable = Runnable { releaseBinding() }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                handler.post { releaseBinding() }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handler.post { releaseBinding() }
            }

            override fun onBindingDied(name: ComponentName?) {
                handler.post { releaseBinding() }
            }

            override fun onNullBinding(name: ComponentName?) {
                handler.post { releaseBinding() }
            }
        }

        Log.d(TAG, "Requesting warm up bind to QSPM service")

        val bound = runCatching {
            appContext.bindService(
                QspmService.createBindIntent(appContext),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrElse { error ->
            Log.w(TAG, "Unable to bind to QSPM service for warm up", error)
            bindingInProgress.set(false)
            return
        }

        if (!bound) {
            Log.w(TAG, "System rejected QSPM warm up binding request")
            bindingInProgress.set(false)
            return
        }

        handler.postDelayed(timeoutRunnable, BIND_TIMEOUT_MS)
    }

    private const val BIND_TIMEOUT_MS = 5_000L
    private const val TAG = "QspmServiceInit"
}
