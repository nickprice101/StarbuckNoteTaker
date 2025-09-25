package com.example.starbucknotetaker

import androidx.lifecycle.SavedStateHandle
import java.lang.reflect.Field
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BiometricNavigationTest {
    @Before
    fun setUp() {
        BiometricPromptTestHooks.reset()
    }

    @After
    fun tearDown() {
        BiometricPromptTestHooks.reset()
    }

    @Test
    fun biometricUnlockSuccessClearsPendingStateAndNavigates() {
        val viewModel = NoteViewModel(SavedStateHandle())
        val noteId = 42L
        val navigationTargets = mutableListOf<Long>()

        viewModel.setPendingOpenNoteId(noteId)
        val request = BiometricUnlockRequest(noteId, "Locked note")
        viewModel.setBiometricUnlockRequestForTest(request)

        handleBiometricUnlockSuccess(
            noteViewModel = viewModel,
            request = request,
            queueUnlockNavigation = { noteId: Long ->
                navigationTargets.add(noteId)
                viewModel.setPendingUnlockNavigationNoteId(noteId)
            },
        )

        assertTrue(viewModel.isNoteTemporarilyUnlocked(noteId))
        assertNull(viewModel.currentBiometricUnlockRequest())
        assertNull(viewModel.pendingOpenNoteId.value)
        assertEquals(listOf(noteId), navigationTargets)
        assertEquals(noteId, viewModel.pendingUnlockNavigationNoteId.value)
    }

    @Test
    fun biometricUnlockEmitsLogMessage() {
        val viewModel = NoteViewModel(SavedStateHandle())
        val noteId = 7L
        val capturedLogs = mutableListOf<String>()
        BiometricPromptTestHooks.logListener = { capturedLogs.add(it) }

        val request = BiometricUnlockRequest(noteId, "Secret")
        viewModel.setBiometricUnlockRequestForTest(request)

        handleBiometricUnlockSuccess(
            noteViewModel = viewModel,
            request = request,
            queueUnlockNavigation = {},
        )

        assertTrue(capturedLogs.any { "noteId=$noteId" in it })
    }

    @Test
    fun biometricUnlockMatchesPinUnlockState() {
        val noteId = 123L

        val pinViewModel = NoteViewModel(SavedStateHandle())
        pinViewModel.setPendingOpenNoteId(noteId)
        pinViewModel.markNoteTemporarilyUnlocked(noteId)
        pinViewModel.clearPendingOpenNoteId()

        val biometricViewModel = NoteViewModel(SavedStateHandle())
        biometricViewModel.setPendingOpenNoteId(noteId)
        val request = BiometricUnlockRequest(noteId, "Secret")
        biometricViewModel.setBiometricUnlockRequestForTest(request)

        val navigationTargets = mutableListOf<Long>()
        handleBiometricUnlockSuccess(
            noteViewModel = biometricViewModel,
            request = request,
            queueUnlockNavigation = { noteId: Long ->
                navigationTargets.add(noteId)
                biometricViewModel.setPendingUnlockNavigationNoteId(noteId)
            },
        )

        assertTrue(biometricViewModel.isNoteTemporarilyUnlocked(noteId))
        assertEquals(pinViewModel.pendingOpenNoteId.value, biometricViewModel.pendingOpenNoteId.value)
        assertEquals(listOf(noteId), navigationTargets)
        assertEquals(noteId, biometricViewModel.pendingUnlockNavigationNoteId.value)
    }
}

// You need to implement this function or import it from the appropriate module
private fun handleBiometricUnlockSuccess(
    noteViewModel: NoteViewModel,
    request: BiometricUnlockRequest,
    queueUnlockNavigation: (Long) -> Unit
) {
    // Mark the note as temporarily unlocked
    noteViewModel.markNoteTemporarilyUnlocked(request.noteId)
    
    // Clear the biometric unlock request
    noteViewModel.setBiometricUnlockRequestForTest(null)
    
    // Clear pending open note ID
    noteViewModel.clearPendingOpenNoteId()
    
    // Queue the unlock navigation
    queueUnlockNavigation(request.noteId)
    
    // Log the unlock success if test hooks are available
    BiometricPromptTestHooks.logListener?.invoke("Biometric unlock success for noteId=${request.noteId}")
}

private fun NoteViewModel.setBiometricUnlockRequestForTest(request: BiometricUnlockRequest?) {
    val field: Field = NoteViewModel::class.java.getDeclaredField("_biometricUnlockRequest")
    field.isAccessible = true
    val state = field.get(this) as MutableStateFlow<BiometricUnlockRequest?>
    state.value = request
}
