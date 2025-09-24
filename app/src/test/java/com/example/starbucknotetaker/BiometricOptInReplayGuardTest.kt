package com.example.starbucknotetaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricOptInReplayGuardTest {

    @Test
    fun clearPendingOptIn_retriggers_whenFlowMatches() {
        val logMessages = mutableListOf<String>()
        val notifiedMessages = mutableListOf<String>()
        val request = BiometricUnlockRequest(noteId = 1L, title = "Secret", token = 7L)
        var currentRequest: BiometricUnlockRequest? = request
        var activeToken: Long? = request.token
        val guard = BiometricOptInReplayGuard(
            logger = { logMessages += it },
            notifyBiometricLog = { notifiedMessages += it },
            currentBiometricUnlockRequest = { currentRequest },
            currentActiveRequestToken = { activeToken },
        )

        guard.confirmPendingOptIn("opt_in_dialog_confirm")
        val result = guard.clearPendingOptIn("note_list_unlock_request")

        assertEquals(BiometricOptInReplayGuard.ClearAction.RETRIGGER, result.action)
        assertEquals(1L, result.promptTrigger)
        assertFalse(result.pendingOptIn)
        assertTrue(result.matchesCurrentFlow)
        assertTrue(result.hasActiveFlow)
        assertTrue(logMessages.any { it.contains("action=observe") })
        assertTrue(logMessages.any { it.contains("action=retrigger") })
        assertTrue(notifiedMessages.any { it.contains("action=retrigger") })
    }

    @Test
    fun clearPendingOptIn_idles_whenNoActiveRequest() {
        val logMessages = mutableListOf<String>()
        var currentRequest: BiometricUnlockRequest? = null
        var activeToken: Long? = 99L
        val guard = BiometricOptInReplayGuard(
            logger = { logMessages += it },
            notifyBiometricLog = {},
            currentBiometricUnlockRequest = { currentRequest },
            currentActiveRequestToken = { activeToken },
        )

        guard.confirmPendingOptIn("opt_in_dialog_confirm")
        val result = guard.clearPendingOptIn("note_list_unlock_request")

        assertEquals(BiometricOptInReplayGuard.ClearAction.IDLE, result.action)
        assertEquals(0L, result.promptTrigger)
        assertFalse(result.pendingOptIn)
        assertFalse(result.hasActiveFlow)
        assertTrue(logMessages.any { it.contains("action=idle") })
    }

    @Test
    fun clearPendingOptIn_idles_whenGuardDisabled() {
        val logMessages = mutableListOf<String>()
        var currentRequest: BiometricUnlockRequest? = BiometricUnlockRequest(
            noteId = 9L,
            title = "Secret",
            token = 11L,
        )
        var activeToken: Long? = currentRequest!!.token
        var guardDisabled = true
        val guard = BiometricOptInReplayGuard(
            logger = { logMessages += it },
            notifyBiometricLog = {},
            currentBiometricUnlockRequest = { currentRequest },
            currentActiveRequestToken = { activeToken },
            isGuardDisabled = { guardDisabled },
        )

        guard.confirmPendingOptIn("opt_in_dialog_confirm")
        val result = guard.clearPendingOptIn("note_list_unlock_request")

        assertEquals(BiometricOptInReplayGuard.ClearAction.IDLE, result.action)
        assertEquals(0L, result.promptTrigger)
        assertFalse(result.pendingOptIn)
        assertTrue(result.hasActiveFlow)
        assertTrue(logMessages.any { it.contains("action=idle") })
    }

    @Test
    fun clearPendingOptIn_forceClearsWhenActiveTokenMissing() {
        val logMessages = mutableListOf<String>()
        val notifiedMessages = mutableListOf<String>()
        val request = BiometricUnlockRequest(
            noteId = 42L,
            title = "Secret",
            token = 5L,
        )
        var currentRequest: BiometricUnlockRequest? = request
        var activeToken: Long? = null
        val guard = BiometricOptInReplayGuard(
            logger = { logMessages += it },
            notifyBiometricLog = { notifiedMessages += it },
            currentBiometricUnlockRequest = { currentRequest },
            currentActiveRequestToken = { activeToken },
        )

        guard.confirmPendingOptIn("opt_in_dialog_confirm")
        val result = guard.clearPendingOptIn("note_list_unlock_request")

        assertEquals(
            BiometricOptInReplayGuard.ClearAction.FORCE_CLEAR_MISSING_ACTIVE_TOKEN,
            result.action,
        )
        assertEquals(0L, result.promptTrigger)
        assertFalse(result.pendingOptIn)
        assertTrue(result.hasActiveFlow)
        assertFalse(result.matchesCurrentFlow)
        assertTrue(logMessages.any { it.contains("force_clear_missing_active_token") })
        assertTrue(notifiedMessages.any { it.contains("force_clear_missing_active_token") })
    }

    @Test
    fun clearPendingOptIn_forceClearsWhenTokenMismatch() {
        val logMessages = mutableListOf<String>()
        val notifiedMessages = mutableListOf<String>()
        val request = BiometricUnlockRequest(
            noteId = 99L,
            title = "Secret",
            token = 3L,
        )
        var currentRequest: BiometricUnlockRequest? = request
        var activeToken: Long? = request.token + 1L
        val guard = BiometricOptInReplayGuard(
            logger = { logMessages += it },
            notifyBiometricLog = { notifiedMessages += it },
            currentBiometricUnlockRequest = { currentRequest },
            currentActiveRequestToken = { activeToken },
        )

        guard.confirmPendingOptIn("opt_in_dialog_confirm")
        val result = guard.clearPendingOptIn("pin_prompt_biometric_request")

        assertEquals(
            BiometricOptInReplayGuard.ClearAction.FORCE_CLEAR_TOKEN_MISMATCH,
            result.action,
        )
        assertEquals(0L, result.promptTrigger)
        assertFalse(result.pendingOptIn)
        assertTrue(result.hasActiveFlow)
        assertFalse(result.matchesCurrentFlow)
        assertTrue(logMessages.any { it.contains("force_clear_token_mismatch") })
        assertTrue(notifiedMessages.any { it.contains("force_clear_token_mismatch") })
    }

    @Test
    fun clearPendingOptIn_retriggersAfterOptInMismatch() {
        val logMessages = mutableListOf<String>()
        val notifiedMessages = mutableListOf<String>()
        val request = BiometricUnlockRequest(
            noteId = 123L,
            title = "Secret",
            token = 8L,
        )
        var currentRequest: BiometricUnlockRequest? = request
        var activeToken: Long? = null
        val guard = BiometricOptInReplayGuard(
            logger = { logMessages += it },
            notifyBiometricLog = { notifiedMessages += it },
            currentBiometricUnlockRequest = { currentRequest },
            currentActiveRequestToken = { activeToken },
        )

        guard.confirmPendingOptIn("opt_in_dialog_confirm")
        val result = guard.clearPendingOptIn("opt_in_authenticated")

        assertEquals(BiometricOptInReplayGuard.ClearAction.RETRIGGER, result.action)
        assertEquals(1L, result.promptTrigger)
        assertFalse(result.pendingOptIn)
        assertTrue(result.hasActiveFlow)
        assertFalse(result.matchesCurrentFlow)
        assertTrue(logMessages.any { it.contains("fallback=opt_in_mismatch") })
        assertTrue(notifiedMessages.any { it.contains("fallback=opt_in_mismatch") })
    }
}
