package com.example.starbucknotetaker

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Attempts to navigate to [noteId] after it has been unlocked. Navigation is delayed until the
 * [lifecycle] reaches at least the [Lifecycle.State.RESUMED] state. Once a navigation attempt has
 * been made the pending navigation request stored in [noteViewModel] is cleared, even if the
 * navigation is cancelled.
 */
suspend fun navigatePendingUnlock(
    lifecycle: Lifecycle,
    noteViewModel: NoteViewModel,
    noteId: Long,
    openNoteAfterUnlock: (Long) -> Unit,
) {
    val pendingId = noteViewModel.pendingUnlockNavigationNoteId.value
    logPendingUnlock(
        "navigatePendingUnlock start noteId=${'$'}noteId pendingId=${'$'}pendingId lifecycle=${'$'}{lifecycle.currentState}"
    )
    if (pendingId != null && pendingId != noteId) {
        logPendingUnlock(
            "navigatePendingUnlock skip_initial_mismatch noteId=${'$'}noteId pendingId=${'$'}pendingId lifecycle=${'$'}{lifecycle.currentState}"
        )
        return
    }

    fun attemptNavigation() {
        val resumedPendingId = noteViewModel.pendingUnlockNavigationNoteId.value
        logPendingUnlock(
            "navigatePendingUnlock resumed noteId=${'$'}noteId pendingId=${'$'}resumedPendingId lifecycle=${'$'}{lifecycle.currentState}"
        )
        if (resumedPendingId != null && resumedPendingId != noteId) {
            logPendingUnlock(
                "navigatePendingUnlock skip_resumed_mismatch noteId=${'$'}noteId pendingId=${'$'}resumedPendingId lifecycle=${'$'}{lifecycle.currentState}"
            )
            return
        }

        var navigationAttempted = false
        try {
            navigationAttempted = true
            logPendingUnlock(
                "navigatePendingUnlock navigating noteId=${'$'}noteId lifecycle=${'$'}{lifecycle.currentState}"
            )
            openNoteAfterUnlock(noteId)
        } finally {
            val currentPendingId = noteViewModel.pendingUnlockNavigationNoteId.value
            if (navigationAttempted && currentPendingId == noteId) {
                logPendingUnlock(
                    "navigatePendingUnlock clearing noteId=${'$'}noteId pendingId=${'$'}currentPendingId lifecycle=${'$'}{lifecycle.currentState}"
                )
                noteViewModel.clearPendingUnlockNavigationNoteId()
            } else {
                logPendingUnlock(
                    "navigatePendingUnlock skip_clearing noteId=${'$'}noteId pendingId=${'$'}currentPendingId navigationAttempted=${'$'}navigationAttempted lifecycle=${'$'}{lifecycle.currentState}"
                )
            }
        }
    }

    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        attemptNavigation()
        return
    }

    logPendingUnlock(
        "navigatePendingUnlock waiting_for_resume noteId=${'$'}noteId pendingId=${'$'}pendingId lifecycle=${'$'}{lifecycle.currentState}"
    )

    suspendCancellableCoroutine<Unit> { continuation ->
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        lifecycle.removeObserver(this)
                        try {
                            attemptNavigation()
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        } catch (throwable: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(throwable)
                            } else {
                                throw throwable
                            }
                        }
                    }

                    Lifecycle.Event.ON_DESTROY -> {
                        lifecycle.removeObserver(this)
                        if (continuation.isActive) {
                            continuation.cancel(CancellationException("Lifecycle destroyed before resume"))
                        }
                    }

                    else -> Unit
                }
            }
        }

        lifecycle.addObserver(observer)
        continuation.invokeOnCancellation { lifecycle.removeObserver(observer) }
    }
}

private fun logPendingUnlock(message: String) {
    try {
        android.util.Log.d(BIOMETRIC_LOG_TAG, message)
    } catch (_: RuntimeException) {
        // android.util.Log throws at runtime in plain unit tests; ignore logging in that environment.
    }
}
