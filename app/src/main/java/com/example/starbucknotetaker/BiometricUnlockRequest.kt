package com.example.starbucknotetaker

const val BIOMETRIC_LOG_TAG = "BiometricFlow"

/**
 * Represents a biometric unlock request for a note. The [token] property defaults to a
 * unique value so that repeated requests for the same note still trigger the biometric
 * prompt.
 */
data class BiometricUnlockRequest(
    val noteId: Long,
    val title: String,
    val token: Long = System.nanoTime(),
)
