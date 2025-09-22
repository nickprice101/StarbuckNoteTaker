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
    if (noteViewModel.pendingUnlockNavigationNoteId.value != noteId) {
        return
    }

    var navigationAttempted = false
    try {
        lifecycle.whenStateAtLeast(Lifecycle.State.RESUMED) {
            if (noteViewModel.pendingUnlockNavigationNoteId.value != noteId) {
                return@whenStateAtLeast
            }

            navigationAttempted = true
            openNoteAfterUnlock(noteId)
        }
    } finally {
        if (navigationAttempted &&
            noteViewModel.pendingUnlockNavigationNoteId.value == noteId
        ) {
            noteViewModel.clearPendingUnlockNavigationNoteId()
        }
    }
}
