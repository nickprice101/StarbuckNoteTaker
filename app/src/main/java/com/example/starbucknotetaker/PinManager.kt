package com.example.starbucknotetaker

import android.content.Context
import android.content.SharedPreferences

class PinManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)

    fun isPinSet(): Boolean = prefs.contains("pin")

    fun setPin(pin: String) {
        prefs.edit().putString("pin", pin).apply()
    }

    fun checkPin(pin: String): Boolean = prefs.getString("pin", null) == pin

    fun getPinLength(): Int = prefs.getString("pin", null)?.length ?: 0
}
