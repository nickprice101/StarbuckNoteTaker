package com.example.starbucknotetaker.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.starbucknotetaker.LlamaEngineProvider
import com.example.starbucknotetaker.LlamaModelManager
import com.example.starbucknotetaker.MainActivity
import com.example.starbucknotetaker.PinManager
import java.io.File
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

    @Test
    fun downloadedModelShowsEngineWarmupProgress() {
        composeRule.activityRule.scenario.onActivity { activity ->
            val pinManager = PinManager(activity.applicationContext)
            val modelPath = File(activity.filesDir, "models/model-lib.so").absolutePath
            activity.setContent {
                StarbuckNoteTakerTheme {
                    SettingsScreen(
                        pinManager = pinManager,
                        biometricEnabled = false,
                        onBiometricChanged = {},
                        summarizerEnabled = true,
                        onSummarizerChanged = {},
                        modelStatus = LlamaModelManager.ModelStatus.Present(
                            path = modelPath,
                            sizeBytes = 2L * 1024L * 1024L * 1024L,
                            abi = "x86_64",
                        ),
                        modelPreloadState = LlamaEngineProvider.PreloadState.Loading,
                        onDownloadModel = {},
                        onDeleteModel = {},
                        isAiCapable = true,
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

        composeRule.onNodeWithText(
            "loading the LiteRT-LM model",
            substring = true,
        ).assertExists()
    }

    @Test
    fun modelDownloadShowsByteLinkedPercentageProgress() {
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
                        modelStatus = LlamaModelManager.ModelStatus.Downloading(
                            progressPercent = 50,
                            label = "Downloading Qwen3 0.6B…",
                            downloadedBytes = 250L * 1024L * 1024L,
                            totalBytes = 500L * 1024L * 1024L,
                            abi = "x86_64",
                        ),
                        onDownloadModel = {},
                        onDeleteModel = {},
                        isAiCapable = true,
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
        composeRule.onNodeWithText("50%  (250 MB / 500 MB)").assertExists()
    }
}
