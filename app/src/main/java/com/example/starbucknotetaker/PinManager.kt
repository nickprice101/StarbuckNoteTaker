package com.example.starbucknotetaker

import android.content.Context
import android.content.SharedPreferences

class PinManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)

    fun isPinSet(): Boolean = prefs.contains(KEY_PIN)

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN, pin).apply()
    }

    fun getStoredPin(): String? = prefs.getString(KEY_PIN, null)

    fun checkPin(pin: String): Boolean = prefs.getString(KEY_PIN, null) == pin

    fun getPinLength(): Int = prefs.getString(KEY_PIN, null)?.length ?: 0

    fun updatePin(oldPin: String, newPin: String): Boolean {
        val stored = prefs.getString(KEY_PIN, null) ?: return false
        return if (stored == oldPin) {
            prefs.edit().putString(KEY_PIN, newPin).apply()
            true
        } else {
            false
        }
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN)
            .remove(KEY_BIOMETRIC_ENABLED)
            .apply()
    }

    companion object {
        private const val KEY_PIN = "pin"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    }
}
