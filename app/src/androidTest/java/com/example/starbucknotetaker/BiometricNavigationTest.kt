package com.example.starbucknotetaker

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class BiometricNavigationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    init {
        val prefs = context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit()
            .putString("pin", "1234")
            .putBoolean("biometric_enabled", true)
            .commit()
        context.getFileStreamPath("notes.enc")?.delete()
        BiometricPromptTestHooks.overrideCanAuthenticate = BiometricManager.BIOMETRIC_SUCCESS
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        BiometricPromptTestHooks.reset()
        BiometricPromptTestHooks.overrideCanAuthenticate = BiometricManager.BIOMETRIC_SUCCESS
        context.getFileStreamPath("notes.enc")?.delete()
        val prefs = context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("pin", "1234")
            .putBoolean("biometric_enabled", true)
            .commit()
    }

    @After
    fun tearDown() {
        BiometricPromptTestHooks.reset()
    }

    @Test
    fun lockedNoteNavigatesAfterBiometricSuccess() {
        val title = "Locked test note"
        val content = "Secret content"
        composeTestRule.activityRule.scenario.onActivity { activity ->
            val viewModel = activity.getNoteViewModelForTest()
            viewModel.addNote(
                title = title,
                content = content,
                images = emptyList(),
                files = emptyList(),
                linkPreviews = emptyList(),
                event = null,
            )
            val noteId = viewModel.notes.first { it.title == title }.id
            viewModel.setNoteLock(noteId, true)
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(title)
                .fetchSemanticsNodes().isNotEmpty()
        }

        val launchedPrompts = mutableListOf<String>()
        BiometricPromptTestHooks.interceptAuthenticate = { promptInfo, callback ->
            val title = promptInfo.title.toString()
            launchedPrompts += title
            assertEquals("Unlock note", title)
            callback.onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult(null))
            true
        }

        composeTestRule.onNodeWithText(title).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithContentDescription("Back")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText(content).assertIsDisplayed()
        assertEquals(listOf("Unlock note"), launchedPrompts)
    }

    @Test
    fun unlockRequestRetriggersAfterOptInClears() {
        val prefs = context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        context.getFileStreamPath("notes.enc")?.delete()

        composeTestRule.activityRule.scenario.recreate()

        val noteTitle = "Opt-in retry note"
        val noteContent = "Opt-in retry content"
        val launchedPrompts = mutableListOf<String>()
        var createdNoteId: Long? = null

        BiometricPromptTestHooks.interceptAuthenticate = { promptInfo, callback ->
            val promptTitle = promptInfo.title.toString()
            launchedPrompts += promptTitle
            when (promptTitle) {
                "Enable biometric unlock" -> {
                    composeTestRule.activityRule.scenario.onActivity { activity ->
                        val viewModel = activity.getNoteViewModelForTest()
                        if (createdNoteId == null) {
                            viewModel.addNote(
                                title = noteTitle,
                                content = noteContent,
                                images = emptyList(),
                                files = emptyList(),
                                linkPreviews = emptyList(),
                                event = null,
                            )
                            val noteId = viewModel.notes.first { it.title == noteTitle }.id
                            createdNoteId = noteId
                            viewModel.setNoteLock(noteId, true)
                        }
                        val noteId = createdNoteId ?: error("note not created")
                        viewModel.requestBiometricUnlock(noteId, noteTitle)
                    }
                    callback.onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult(null))
                    true
                }
                "Unlock note" -> {
                    callback.onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult(null))
                    true
                }
                else -> false
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput("1234")
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("1234")
        composeTestRule.onNodeWithText("Save").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Enable biometric unlock?").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Enable").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(noteContent).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(noteContent).assertIsDisplayed()
        assertEquals(listOf("Enable biometric unlock", "Unlock note"), launchedPrompts)
    }
}
