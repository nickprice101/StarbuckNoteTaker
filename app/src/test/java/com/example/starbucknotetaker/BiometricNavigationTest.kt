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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
            openNoteAfterUnlock = { navigationTargets += it },
        )

        assertTrue(viewModel.isNoteTemporarilyUnlocked(noteId))
        assertNull(viewModel.currentBiometricUnlockRequest())
        assertNull(viewModel.pendingOpenNoteId.value)
        assertEquals(listOf(noteId), navigationTargets)
    }

    @Test
    fun biometricUnlockEmitsLogMessage() {
        val viewModel = NoteViewModel(SavedStateHandle())
        val noteId = 7L
        val capturedLogs = mutableListOf<String>()
        BiometricPromptTestHooks.logListener = { capturedLogs += it }

        val request = BiometricUnlockRequest(noteId, "Secret")
        viewModel.setBiometricUnlockRequestForTest(request)

        handleBiometricUnlockSuccess(
            noteViewModel = viewModel,
            request = request,
            openNoteAfterUnlock = {},
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
            openNoteAfterUnlock = { navigationTargets += it },
        )

        assertTrue(biometricViewModel.isNoteTemporarilyUnlocked(noteId))
        assertEquals(pinViewModel.pendingOpenNoteId.value, biometricViewModel.pendingOpenNoteId.value)
        assertEquals(listOf(noteId), navigationTargets)
    }
}

private fun NoteViewModel.setBiometricUnlockRequestForTest(request: BiometricUnlockRequest?) {
    val field: Field = NoteViewModel::class.java.getDeclaredField("_biometricUnlockRequest")
    field.isAccessible = true
    val state = field.get(this) as MutableStateFlow<BiometricUnlockRequest?>
    state.value = request
}
