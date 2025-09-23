package com.example.starbucknotetaker

import androidx.annotation.VisibleForTesting
import androidx.biometric.BiometricPrompt

/**
 * Provides escape hatches for instrumentation tests interacting with the biometric prompt flow.
 */
object BiometricPromptTestHooks {
    @Volatile
    var interceptAuthenticate: ((BiometricPrompt.PromptInfo, BiometricPrompt.AuthenticationCallback) -> Boolean)? = null

    @Volatile
    var overrideCanAuthenticate: Int? = null

    @VisibleForTesting
    fun reset() {
        interceptAuthenticate = null
        overrideCanAuthenticate = null
    }
}
