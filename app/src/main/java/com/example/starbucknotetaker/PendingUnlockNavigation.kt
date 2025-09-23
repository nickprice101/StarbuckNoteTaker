package com.example.starbucknotetaker

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.whenStateAtLeast

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

    var navigationAttempted = false
    try {
        lifecycle.whenStateAtLeast(Lifecycle.State.RESUMED) {
            val resumedPendingId = noteViewModel.pendingUnlockNavigationNoteId.value
            logPendingUnlock(
                "navigatePendingUnlock resumed noteId=${'$'}noteId pendingId=${'$'}resumedPendingId lifecycle=${'$'}{lifecycle.currentState}"
            )
            if (resumedPendingId != null && resumedPendingId != noteId) {
                logPendingUnlock(
                    "navigatePendingUnlock skip_resumed_mismatch noteId=${'$'}noteId pendingId=${'$'}resumedPendingId lifecycle=${'$'}{lifecycle.currentState}"
                )
                return@whenStateAtLeast
            }

            navigationAttempted = true
            logPendingUnlock(
                "navigatePendingUnlock navigating noteId=${'$'}noteId lifecycle=${'$'}{lifecycle.currentState}"
            )
            openNoteAfterUnlock(noteId)
        }
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

private fun logPendingUnlock(message: String) {
    try {
        android.util.Log.d(BIOMETRIC_LOG_TAG, message)
    } catch (_: RuntimeException) {
        // android.util.Log throws at runtime in plain unit tests; ignore logging in that environment.
    }
}
