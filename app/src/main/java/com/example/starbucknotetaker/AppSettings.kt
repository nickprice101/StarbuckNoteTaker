package com.example.starbucknotetaker

import android.content.Context
import androidx.core.content.edit

class AppSettings(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSummarizerEnabled(): Boolean =
        prefs.getBoolean(KEY_SUMMARIZER_ENABLED, /* defaultValue = */ true)

    fun setSummarizerEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SUMMARIZER_ENABLED, enabled) }
    }

    private companion object {
        private const val PREFS_NAME = "starbuck_settings"
        private const val KEY_SUMMARIZER_ENABLED = "summarizer_enabled"
    }
}
