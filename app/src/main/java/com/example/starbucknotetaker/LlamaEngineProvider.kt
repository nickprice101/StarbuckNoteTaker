package com.example.starbucknotetaker

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-local holder for the heavy MLC engine.
 *
 * Keeping one engine warm for the app process avoids repeated model reloads
 * when the user asks a follow-up question or rewrites several notes in a row.
 */
object LlamaEngineProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    sealed class PreloadState {
        object Idle : PreloadState()
        object Loading : PreloadState()
        data class Ready(val elapsedMs: Long) : PreloadState()
        object ModelUnavailable : PreloadState()
        data class Failed(val message: String) : PreloadState()
    }

    private val _preloadState = MutableStateFlow<PreloadState>(PreloadState.Idle)
    val preloadState: StateFlow<PreloadState> = _preloadState

    private var engine: LlamaEngine? = null
    private var idleCloseJob: Job? = null
    private var preloadJob: Job? = null
    private var keepWarmForProcess = false

    fun acquire(context: Context): LlamaEngine =
        synchronized(lock) {
            idleCloseJob?.cancel()
            idleCloseJob = null
            engine ?: LlamaEngine(context.applicationContext).also { engine = it }
        }

    /**
     * Starts loading and priming the model before the user asks a question.
     * Missing weights are detected cheaply; a completed download can call this
     * method again to begin the real preload.
     */
    fun prewarm(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            keepWarmForProcess = true
            idleCloseJob?.cancel()
            idleCloseJob = null
            if (engine?.isWarm == true) {
                _preloadState.value = PreloadState.Ready(0L)
                return
            }
            if (preloadJob?.isActive == true) return

            _preloadState.value = PreloadState.Loading
            preloadJob = scope.launch {
                // Application.onCreate must remain lightweight. Delay briefly so Android can
                // finish binding the process, then construct and load the engine entirely away
                // from the main thread. This avoids startup ANRs on slower storage/emulators.
                delay(STARTUP_PREWARM_DELAY_MS)
                val startedAt = SystemClock.elapsedRealtime()
                val target = acquire(appContext)
                // Model loading and priming are opportunistic background work. Giving this
                // thread background priority ensures an immediate lookup, editor action, or
                // UI frame wins CPU time even when warm-up is already under way. Native TVM
                // worker threads are created from this thread and inherit its scheduling nice.
                val originalPriority = runCatching {
                    Process.getThreadPriority(Process.myTid())
                }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                val outcome = try {
                    runCatching { target.warmUp() }
                } finally {
                    runCatching { Process.setThreadPriority(originalPriority) }
                }
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                synchronized(lock) {
                    preloadJob = null
                    _preloadState.value = outcome.fold(
                        onSuccess = { ready ->
                            if (ready) PreloadState.Ready(elapsedMs) else PreloadState.ModelUnavailable
                        },
                        onFailure = { failure ->
                            PreloadState.Failed(failure.message ?: failure::class.java.simpleName)
                        },
                    )
                }
                outcome.onSuccess { ready ->
                    Log.i(TAG, "Model preload finished ready=$ready elapsedMs=$elapsedMs")
                }.onFailure { failure ->
                    Log.e(TAG, "Model preload failed after ${elapsedMs}ms", failure)
                }
            }
        }
    }

    fun releaseAfterIdle(idleMillis: Long = DEFAULT_IDLE_CLOSE_MS) {
        synchronized(lock) {
            if (keepWarmForProcess) return
            idleCloseJob?.cancel()
            idleCloseJob = scope.launch {
                delay(idleMillis)
                val toClose = synchronized(lock) {
                    idleCloseJob = null
                    val current = engine
                    engine = null
                    current
                }
                toClose?.close()
            }
        }
    }

    fun closeNow() {
        val toClose = synchronized(lock) {
            keepWarmForProcess = false
            preloadJob?.cancel()
            preloadJob = null
            idleCloseJob?.cancel()
            idleCloseJob = null
            _preloadState.value = PreloadState.Idle
            val current = engine
            engine = null
            current
        }
        toClose?.close()
    }

    private const val DEFAULT_IDLE_CLOSE_MS = 5 * 60 * 1000L
    private const val STARTUP_PREWARM_DELAY_MS = 750L
    private const val TAG = "LlamaEngineProvider"
}
