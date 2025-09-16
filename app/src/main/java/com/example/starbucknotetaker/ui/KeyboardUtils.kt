package com.example.starbucknotetaker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Provides a convenient lambda for dismissing the on-screen keyboard and
 * clearing the current focus owner. Useful before navigating away from screens
 * that contain editable text fields to avoid Compose's IME dispatcher warnings.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun rememberKeyboardHider(): () -> Unit {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    return remember(keyboardController, focusManager) {
        {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }
}

