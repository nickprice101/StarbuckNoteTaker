package com.example.starbucknotetaker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-local holder for the heavy MLC engine.
 *
 * Keeping one engine warm for a short idle window avoids repeated model reloads
 * when the user asks a follow-up question or rewrites several notes in a row.
 */
internal object LlamaEngineProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private var engine: LlamaEngine? = null
    private var idleCloseJob: Job? = null

    fun acquire(context: Context): LlamaEngine =
        synchronized(lock) {
            idleCloseJob?.cancel()
            idleCloseJob = null
            engine ?: LlamaEngine(context.applicationContext).also { engine = it }
        }

    fun releaseAfterIdle(idleMillis: Long = DEFAULT_IDLE_CLOSE_MS) {
        synchronized(lock) {
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
            idleCloseJob?.cancel()
            idleCloseJob = null
            val current = engine
            engine = null
            current
        }
        toClose?.close()
    }

    private const val DEFAULT_IDLE_CLOSE_MS = 5 * 60 * 1000L
}
