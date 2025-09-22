package com.example.starbucknotetaker

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.testing.TestLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingUnlockNavigationTest {
    @Test
    fun pendingUnlockNavigationClearsIdAndNavigatesAgainAfterCancellation() {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val lifecycleOwner = TestLifecycleOwner(Lifecycle.State.RESUMED)
                val viewModel = NoteViewModel(SavedStateHandle())
                val noteId = 42L

                viewModel.setPendingUnlockNavigationNoteId(noteId)
                var navigationCount = 0

                val cancelledNavigation = launch {
                    try {
                        navigatePendingUnlock(
                            lifecycle = lifecycleOwner.lifecycle,
                            noteViewModel = viewModel,
                            noteId = noteId,
                            openNoteAfterUnlock = {
                                navigationCount++
                                throw CancellationException("Simulated navigation cancellation")
                            },
                        )
                    } catch (_: CancellationException) {
                        // Expected due to the simulated cancellation above.
                    }
                }

                cancelledNavigation.join()

                assertEquals(1, navigationCount)
                assertNull(viewModel.pendingUnlockNavigationNoteId.value)

                viewModel.setPendingUnlockNavigationNoteId(noteId)

                navigatePendingUnlock(
                    lifecycle = lifecycleOwner.lifecycle,
                    noteViewModel = viewModel,
                    noteId = noteId,
                    openNoteAfterUnlock = { navigationCount++ },
                )

                assertEquals(2, navigationCount)
                assertNull(viewModel.pendingUnlockNavigationNoteId.value)
            } finally {
                Dispatchers.resetMain()
            }
        }
    }
}
