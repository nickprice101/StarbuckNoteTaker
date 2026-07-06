package com.example.starbucknotetaker.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.starbucknotetaker.LlamaModelManager
import com.example.starbucknotetaker.MainActivity
import com.example.starbucknotetaker.PinManager
import org.junit.Rule
import org.junit.Test

class SettingsScreenInstrumentationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun nonAiCapableDeviceShowsUnavailableMessage() {
        composeRule.activityRule.scenario.onActivity { activity ->
            val pinManager = PinManager(activity.applicationContext)
            activity.setContent {
                StarbuckNoteTakerTheme {
                    SettingsScreen(
                        pinManager = pinManager,
                        biometricEnabled = false,
                        onBiometricChanged = {},
                        summarizerEnabled = true,
                        onSummarizerChanged = {},
                        modelStatus = LlamaModelManager.ModelStatus.Missing,
                        onDownloadModel = {},
                        onDeleteModel = {},
                        isAiCapable = false,
                        onBack = {},
                        onImport = { _, _, _ -> false },
                        onExport = {},
                        onDisablePinCheck = {},
                        onEnablePinCheck = {},
                        onPinChanged = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText("AI features are not available", substring = true).assertExists()
        composeRule.onNodeWithText("Download AI model").assertDoesNotExist()
    }
}
