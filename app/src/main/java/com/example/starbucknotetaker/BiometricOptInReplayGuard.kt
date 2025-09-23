package com.example.starbucknotetaker

import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Maintains the biometric opt-in replay guard state and logging side effects.
 */
class BiometricOptInReplayGuard(
    private val logger: (String) -> Unit,
    private val notifyBiometricLog: (String) -> Unit,
    private val currentBiometricUnlockRequest: () -> BiometricUnlockRequest?,
    private val currentActiveRequestToken: () -> Long?,
    private val isGuardDisabled: () -> Boolean = { BiometricPromptTestHooks.disableOptInReplayGuard },
) {
    private val _pendingOptIn = mutableStateOf(false)
    val pendingOptIn: State<Boolean> = _pendingOptIn

    private val _promptTrigger = mutableLongStateOf(0L)
    val promptTrigger: LongState = _promptTrigger

    enum class ClearAction {
        RETRIGGER,
        IDLE,
        FORCE_CLEAR_MISSING_ACTIVE_TOKEN,
        FORCE_CLEAR_TOKEN_MISMATCH,
        NOOP,
    }

    data class ClearResult(
        val action: ClearAction,
        val pendingOptIn: Boolean,
        val promptTrigger: Long,
        val matchesCurrentFlow: Boolean,
        val hasActiveFlow: Boolean,
    )

    fun confirmPendingOptIn(reason: String) {
        check(!_pendingOptIn.value) {
            "confirmPendingOptIn while pending"
        }
        _pendingOptIn.value = true
        log("confirmPendingBiometricOptIn reason=$reason")
    }

    fun clearPendingOptIn(reason: String): ClearResult {
        val previous = _pendingOptIn.value
        val triggerBefore = _promptTrigger.longValue
        val pendingRequest = currentBiometricUnlockRequest()
        val activeRequestToken = currentActiveRequestToken()
        val matchesCurrentFlow =
            pendingRequest != null && activeRequestToken != null && pendingRequest.token == activeRequestToken

        if (
            previous &&
            (reason == "note_list_unlock_request" || reason == "pin_prompt_biometric_request")
        ) {
            val guardLog =
                "biometricOptInReplayGuard reason=$reason previous=$previous requestNoteId=${pendingRequest?.noteId} " +
                    "requestToken=${pendingRequest?.token} activeToken=$activeRequestToken matchesCurrentFlow=$matchesCurrentFlow " +
                    "triggerBefore=$triggerBefore action=observe"
            log(guardLog)
        }

        val hasActiveFlow = previous && pendingRequest != null
        val guardDisabled = isGuardDisabled()
        val shouldRetrigger = hasActiveFlow && matchesCurrentFlow && !guardDisabled
        val action: ClearAction

        if (shouldRetrigger) {
            val currentRequest = checkNotNull(pendingRequest)
            _pendingOptIn.value = false
            val triggerAfter = triggerBefore + 1
            _promptTrigger.longValue = triggerAfter
            action = ClearAction.RETRIGGER
            val logMessage =
                "clearPendingBiometricOptIn reason=$reason previous=$previous pendingAfter=${_pendingOptIn.value} " +
                    "requestNoteId=${currentRequest.noteId} requestToken=${currentRequest.token} activeToken=$activeRequestToken " +
                    "matchesCurrentFlow=$matchesCurrentFlow triggerBefore=$triggerBefore triggerAfter=$triggerAfter action=retrigger"
            log(logMessage)
        } else {
            val logAction = when {
                hasActiveFlow -> {
                    if (guardDisabled) {
                        ClearAction.IDLE
                    } else {
                        when (activeRequestToken) {
                            null -> ClearAction.FORCE_CLEAR_MISSING_ACTIVE_TOKEN
                            else -> ClearAction.FORCE_CLEAR_TOKEN_MISMATCH
                        }
                    }
                }
                previous -> ClearAction.IDLE
                else -> ClearAction.NOOP
            }
            action = logAction
            _pendingOptIn.value = false
            val logMessage =
                "clearPendingBiometricOptIn reason=$reason previous=$previous pendingAfter=${_pendingOptIn.value} " +
                    "requestNoteId=${pendingRequest?.noteId} requestToken=${pendingRequest?.token} activeToken=$activeRequestToken " +
                    "matchesCurrentFlow=$matchesCurrentFlow triggerBefore=$triggerBefore action=${action.toLogValue()}"
            log(logMessage)
        }

        return ClearResult(
            action = action,
            pendingOptIn = _pendingOptIn.value,
            promptTrigger = _promptTrigger.longValue,
            matchesCurrentFlow = matchesCurrentFlow,
            hasActiveFlow = hasActiveFlow,
        )
    }

    fun onBiometricUnlockEvent(): Long {
        val nextTrigger = _promptTrigger.longValue + 1
        _promptTrigger.longValue = nextTrigger
        return nextTrigger
    }

    private fun log(message: String) {
        logger(message)
        notifyBiometricLog(message)
    }

    private fun ClearAction.toLogValue(): String =
        when (this) {
            ClearAction.RETRIGGER -> "retrigger"
            ClearAction.IDLE -> "idle"
            ClearAction.FORCE_CLEAR_MISSING_ACTIVE_TOKEN -> "force_clear_missing_active_token"
            ClearAction.FORCE_CLEAR_TOKEN_MISMATCH -> "force_clear_token_mismatch"
            ClearAction.NOOP -> "noop"
        }
}
