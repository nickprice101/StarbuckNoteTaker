package com.example.starbucknotetaker

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
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
        override fun isPinSet(): Boolean {
            enforceCaller()
            return pinManager.isPinSet()
        }

        override fun getPinLength(): Int {
            enforceCaller()
            return pinManager.getPinLength()
        }

        override fun verifyPin(pin: String?): Boolean {
            enforceCaller()
            return pin?.let { pinManager.checkPin(it) } ?: false
        }

        override fun storePin(pin: String?): Boolean {
            enforceCaller()
            val candidate = pin?.trim() ?: return false
            if (!isValidPin(candidate) || pinManager.isPinSet()) {
                return false
            }
            pinManager.setPin(candidate)
            return true
        }

        override fun updatePin(oldPin: String?, newPin: String?): Boolean {
            enforceCaller()
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
            enforceCaller()
            pinManager.clearPin()
        }

        override fun getSummarizerState(): Int {
            enforceCaller()
            return mapState(obtainSummarizer().state.value)
        }

        override fun warmUpSummarizer(): Int {
            enforceCaller()
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
            enforceCaller()
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
            val identity = Binder.clearCallingIdentity()
            try {
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
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        val action = intent?.action
        if (action != null && action !in ACCEPTED_ACTIONS) {
            Log.w(TAG, "Rejecting QSPM bind for unexpected action $action")
            return null
        }
        Log.d(TAG, "QSPM binder connection established${action?.let { " for $it" } ?: ""}")
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

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) {
            return
        }
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)?.toSet()
        }.getOrNull()
        if (packages.isNullOrEmpty() || !packages.contains(packageName)) {
            throw SecurityException("Unauthorized caller (uid=$uid) for QSPM service")
        }
    }

    companion object {
        private const val TAG = "QspmService"
        private val ACCEPTED_ACTIONS = setOf(
            "com.example.starbucknotetaker.IQspmService",
            "com.qualcomm.qspm.IQspmService"
        )
    }
}
