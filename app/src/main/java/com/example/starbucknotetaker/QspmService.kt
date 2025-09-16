package com.example.starbucknotetaker

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Implementation of the QSPM AIDL service.
 *
 * The service bridges binder clients to local application facilities:
 *  - PIN management is handled through [PinManager]
 *  - Summarization requests are delegated to the on-device [Summarizer]
 *
 * Operations run on a background [CoroutineScope] so callers never block the
 * binder thread. Results are dispatched via the generated AIDL stubs.
 */
class QspmService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pinManager by lazy { PinManager(applicationContext) }
    private val summarizerRef = AtomicReference<Summarizer?>()

    @Volatile
    private var warmUpJob: Job? = null

    private val binder = object : IQspmService.Stub() {
        override fun isPinSet(): Boolean = pinManager.isPinSet()

        override fun getPinLength(): Int = pinManager.getPinLength()

        override fun verifyPin(pin: String?): Boolean = pin?.let { pinManager.checkPin(it) } ?: false

        override fun storePin(pin: String?): Boolean {
            val candidate = pin?.trim() ?: return false
            if (!isValidPin(candidate) || pinManager.isPinSet()) {
                return false
            }
            pinManager.setPin(candidate)
            return true
        }

        override fun updatePin(oldPin: String?, newPin: String?): Boolean {
            val newCandidate = newPin?.trim() ?: return false
            if (!isValidPin(newCandidate)) return false
            val current = pinManager.isPinSet()
            return if (!current) {
                pinManager.setPin(newCandidate)
                true
            } else {
                val oldCandidate = oldPin ?: return false
                pinManager.updatePin(oldCandidate, newCandidate)
            }
        }

        override fun clearPin() {
            pinManager.clearPin()
        }

        override fun getSummarizerState(): Int = mapState(obtainSummarizer().state.value)

        override fun warmUpSummarizer(): Int {
            val summarizer = obtainSummarizer()
            val current = summarizer.state.value
            if (current is Summarizer.SummarizerState.Ready) {
                return IQspmService.STATE_READY
            }
            if (warmUpJob?.isActive != true) {
                warmUpJob = serviceScope.launch {
                    try {
                        summarizer.warmUp()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to warm up summarizer", t)
                    } finally {
                        warmUpJob = null
                    }
                }
            }
            return mapState(summarizer.state.value)
        }

        override fun summarize(text: String?, callback: IQspmSummaryCallback?) {
            if (callback == null) {
                Log.w(TAG, "summarize called without callback")
                return
            }
            val input = text?.takeIf { it.isNotBlank() }
            if (input == null) {
                deliverError(callback, "Text must not be empty")
                return
            }
            val summarizer = obtainSummarizer()
            serviceScope.launch {
                try {
                    val summary = summarizer.summarize(input)
                    val fallback = summarizer.state.value is Summarizer.SummarizerState.Fallback
                    deliverSuccess(callback, summary, fallback)
                } catch (t: Throwable) {
                    Log.e(TAG, "Summarization failed", t)
                    deliverError(callback, t.message ?: "Failed to summarize text")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "QSPM binder connection established")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        warmUpJob?.cancel()
        serviceScope.cancel()
        summarizerRef.getAndSet(null)?.close()
    }

    private fun obtainSummarizer(): Summarizer {
        while (true) {
            summarizerRef.get()?.let { return it }
            val created = Summarizer(applicationContext)
            if (summarizerRef.compareAndSet(null, created)) {
                return created
            }
            created.close()
        }
    }

    private fun isValidPin(pin: String): Boolean = pin.length in 4..6 && pin.all { it.isDigit() }

    private fun mapState(state: Summarizer.SummarizerState): Int = when (state) {
        Summarizer.SummarizerState.Ready -> IQspmService.STATE_READY
        Summarizer.SummarizerState.Loading -> IQspmService.STATE_LOADING
        Summarizer.SummarizerState.Fallback -> IQspmService.STATE_FALLBACK
        is Summarizer.SummarizerState.Error -> IQspmService.STATE_ERROR
    }

    private fun deliverError(callback: IQspmSummaryCallback, message: String) {
        try {
            callback.onError(message)
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to deliver summary error", e)
        }
    }

    private fun deliverSuccess(callback: IQspmSummaryCallback, summary: String, fallback: Boolean) {
        try {
            callback.onComplete(summary, fallback)
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to deliver summary result", e)
        }
    }

    companion object {
        private const val TAG = "QspmService"
    }
}
