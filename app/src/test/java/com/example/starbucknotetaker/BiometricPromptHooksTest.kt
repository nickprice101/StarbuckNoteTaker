package com.example.starbucknotetaker

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BiometricPromptHooksTest {
    @Before
    fun setUp() {
        BiometricPromptTestHooks.reset()
    }

    @After
    fun tearDown() {
        BiometricPromptTestHooks.reset()
    }

    @Test
    fun resetClearsOverrides() {
        BiometricPromptTestHooks.interceptAuthenticate = { _, _ -> true }
        BiometricPromptTestHooks.overrideCanAuthenticate = 42
        BiometricPromptTestHooks.logListener = { }
        BiometricPromptTestHooks.disableOptInReplayGuard = true

        BiometricPromptTestHooks.reset()

        assertNull(BiometricPromptTestHooks.interceptAuthenticate)
        assertNull(BiometricPromptTestHooks.overrideCanAuthenticate)
        assertNull(BiometricPromptTestHooks.logListener)
        assertFalse(BiometricPromptTestHooks.disableOptInReplayGuard)
    }

    @Test
    fun notifyBiometricLogInvokesListener() {
        val logs = mutableListOf<String>()
        BiometricPromptTestHooks.logListener = { logs += it }

        BiometricPromptTestHooks.notifyBiometricLog("unlock success")

        assertEquals(listOf("unlock success"), logs)
    }

    @Test
    fun interceptAuthenticateShortCircuits() {
        var intercepted = false
        BiometricPromptTestHooks.interceptAuthenticate = { _, _ ->
            intercepted = true
            true
        }

        val handler = BiometricPromptTestHooks.interceptAuthenticate
        val result = handler?.invoke(Any(), Any()) ?: false

        assertTrue(intercepted)
        assertTrue(result)
    }
}
