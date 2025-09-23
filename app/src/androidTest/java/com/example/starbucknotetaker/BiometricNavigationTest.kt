package com.example.starbucknotetaker

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

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
            callback.onAuthenticationSucceeded(createAuthenticationResult())
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
        val noteTitle = "Opt-in retry note"
        val noteContent = "Opt-in retry content"

        val result = runOptInUnlockFlow(
            disableReplayGuard = false,
            noteTitle = noteTitle,
            noteContent = noteContent,
        )

        assertTrue("Expected the locked note to open after opt-in", result.noteDisplayed)
        assertEquals(listOf("Enable biometric unlock", "Unlock note"), result.launchedPrompts)

        val suppressedLogs = result.biometricLogs.filter {
            it.contains("biometric unlock request suppressed")
        }
        assertTrue("Expected the unlock request to be suppressed while opt-in was pending", suppressedLogs.isNotEmpty())

        val clearLogs = result.biometricLogs.filter {
            it.contains("clearPendingBiometricOptIn") && it.contains("previous=true")
        }
        assertTrue("Expected clearPendingBiometricOptIn logs with previous=true", clearLogs.isNotEmpty())
        assertTrue(clearLogs.none { it.contains("action=idle") })

        val lastClearIndex = result.biometricLogs.withIndex().indexOfLast { (index, log) ->
            index >= 0 && log.contains("clearPendingBiometricOptIn") && log.contains("previous=true")
        }
        val replayLaunchIndex = result.biometricLogs.withIndex().indexOfFirst { (index, log) ->
            index > lastClearIndex && log.contains("Launching biometric prompt") && log.contains("pendingOptIn=false")
        }
        assertTrue(
            "Expected Launching biometric prompt…pendingOptIn=false after opt-in cleared",
            replayLaunchIndex != -1
        )
    }

    @Test
    fun unlockRequestReplayGuardResolvesRegression() {
        val regression = runOptInUnlockFlow(
            disableReplayGuard = true,
            noteTitle = "Opt-in regression note",
            noteContent = "Opt-in regression content",
        )

        assertTrue(regression.launchedPrompts.contains("Enable biometric unlock"))
        assertTrue(
            regression.biometricLogs.any {
                it.contains("clearPendingBiometricOptIn") &&
                    it.contains("previous=true") &&
                    it.contains("action=idle")
            }
        )
        assertTrue(regression.biometricLogs.none {
            it.contains("Launching biometric prompt") && it.contains("pendingOptIn=false")
        })
        assertTrue("Expected navigation to be blocked when the guard is disabled", !regression.noteDisplayed)

        val resolved = runOptInUnlockFlow(
            disableReplayGuard = false,
            noteTitle = "Opt-in regression note resolved",
            noteContent = "Opt-in regression content resolved",
        )

        assertTrue("Expected the guard to restore navigation", resolved.noteDisplayed)
        assertTrue(
            resolved.biometricLogs.any {
                it.contains("Launching biometric prompt") && it.contains("pendingOptIn=false")
            }
        )
        assertTrue(
            resolved.biometricLogs.none {
                it.contains("clearPendingBiometricOptIn") &&
                    it.contains("previous=true") &&
                    it.contains("action=idle")
            }
        )
    }

    @Test
    fun biometricUnlockPromptDoesNotRetriggerWhenOptInInactive() {
        val prefs = context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        context.getFileStreamPath("notes.enc")?.delete()

        composeTestRule.activityRule.scenario.recreate()

        val launchedPrompts = mutableListOf<String>()
        val biometricLogs = mutableListOf<String>()

        BiometricPromptTestHooks.interceptAuthenticate = { promptInfo, callback ->
            val title = promptInfo.title.toString()
            launchedPrompts += title
            when (title) {
                "Enable biometric unlock",
                "Disable biometric unlock",
                "Unlock note" -> {
                    callback.onAuthenticationSucceeded(createAuthenticationResult())
                    true
                }
                else -> false
            }
        }
        BiometricPromptTestHooks.logListener = { message ->
            if (
                message.contains("clearPendingBiometricOptIn") ||
                message.contains("biometric unlock request suppressed") ||
                message.contains("Launching biometric prompt")
            ) {
                biometricLogs += message
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

        val noteTitle = "Loop guard note"
        val noteContent = "Loop guard content"

        composeTestRule.activityRule.scenario.onActivity { activity ->
            val viewModel = activity.getNoteViewModelForTest()
            viewModel.addNote(
                title = noteTitle,
                content = noteContent,
                images = emptyList(),
                files = emptyList(),
                linkPreviews = emptyList(),
                event = null,
            )
            val noteId = viewModel.notes.first { it.title == noteTitle }.id
            viewModel.setNoteLock(noteId, true)
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(noteTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(noteTitle).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(noteContent).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(noteContent).assertIsDisplayed()

        assertEquals(listOf("Enable biometric unlock", "Unlock note"), launchedPrompts)

        val noteListLogs = biometricLogs.filter { it.contains("reason=note_list_unlock_request") }
        assertTrue(noteListLogs.isNotEmpty())
        assertTrue(noteListLogs.all { it.contains("action=idle") })

        val launchLogs = biometricLogs.filter { it.contains("Launching biometric prompt noteId") }
        assertTrue(launchLogs.isNotEmpty())
        assertTrue(launchLogs.last().contains("pendingOptIn=false"))

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithContentDescription("Settings").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("biometric_toggle").assertIsOn()
        composeTestRule.onNodeWithTag("biometric_toggle").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            launchedPrompts.contains("Disable biometric unlock")
        }
        composeTestRule.onNodeWithTag("biometric_toggle").assertIsOff()

        composeTestRule.onNodeWithTag("biometric_toggle").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            launchedPrompts.count { it == "Enable biometric unlock" } >= 2
        }
        composeTestRule.onNodeWithTag("biometric_toggle").assertIsOn()

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(noteTitle).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText(noteTitle).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(noteContent).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(noteContent).assertIsDisplayed()

        val expectedPrompts = listOf(
            "Enable biometric unlock",
            "Unlock note",
            "Disable biometric unlock",
            "Enable biometric unlock",
            "Unlock note",
        )
        assertEquals(expectedPrompts, launchedPrompts)

        val noteListLogsAfterToggle = biometricLogs.filter { it.contains("reason=note_list_unlock_request") }
        assertEquals(2, noteListLogsAfterToggle.size)
        noteListLogsAfterToggle.forEach { log ->
            assertTrue(log.contains("action=idle"))
        }

        val launchLogsAfterToggle = biometricLogs.filter { it.contains("Launching biometric prompt noteId") }
        assertTrue(launchLogsAfterToggle.takeLast(2).all { it.contains("pendingOptIn=false") })
        assertTrue(biometricLogs.none { it.contains("biometric unlock request suppressed") })
    }

    private data class BiometricOptInFlowResult(
        val launchedPrompts: List<String>,
        val biometricLogs: List<String>,
        val noteDisplayed: Boolean,
    )

    private fun createAuthenticationResult(): BiometricPrompt.AuthenticationResult {
        val constructor = BiometricPrompt.AuthenticationResult::class.java.getDeclaredConstructor(
            BiometricPrompt.CryptoObject::class.java
        )
        constructor.isAccessible = true
        return constructor.newInstance(null)
    }

    private fun runOptInUnlockFlow(
        disableReplayGuard: Boolean,
        noteTitle: String,
        noteContent: String,
    ): BiometricOptInFlowResult {
        val prefs = context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        context.getFileStreamPath("notes.enc")?.delete()

        composeTestRule.activityRule.scenario.recreate()

        val launchedPrompts = Collections.synchronizedList(mutableListOf<String>())
        val biometricLogs = Collections.synchronizedList(mutableListOf<String>())
        val pendingOptInCallback = AtomicReference<(() -> Unit)?>(null)

        BiometricPromptTestHooks.disableOptInReplayGuard = disableReplayGuard
        BiometricPromptTestHooks.logListener = { message ->
            if (
                message.contains("clearPendingBiometricOptIn") ||
                message.contains("biometric unlock request suppressed") ||
                message.contains("Launching biometric prompt")
            ) {
                biometricLogs += message
            }
        }
        BiometricPromptTestHooks.interceptAuthenticate = { promptInfo, callback ->
            val promptTitle = promptInfo.title.toString()
            launchedPrompts += promptTitle
            when (promptTitle) {
                "Enable biometric unlock" -> {
                    pendingOptInCallback.set {
                        callback.onAuthenticationSucceeded(createAuthenticationResult())
                    }
                    true
                }
                "Unlock note" -> {
                    callback.onAuthenticationSucceeded(createAuthenticationResult())
                    true
                }
                else -> false
            }
        }

        try {
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

            composeTestRule.activityRule.scenario.onActivity { activity ->
                val viewModel = activity.getNoteViewModelForTest()
                viewModel.addNote(
                    title = noteTitle,
                    content = noteContent,
                    images = emptyList(),
                    files = emptyList(),
                    linkPreviews = emptyList(),
                    event = null,
                )
                val noteId = viewModel.notes.first { it.title == noteTitle }.id
                viewModel.setNoteLock(noteId, true)
            }

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithText(noteTitle).fetchSemanticsNodes().isNotEmpty()
            }

            composeTestRule.onNodeWithText("Enable").performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                pendingOptInCallback.get() != null
            }

            val suppressedLogsBeforeClick = biometricLogs.count {
                it.contains("biometric unlock request suppressed")
            }

            composeTestRule.onNodeWithText(noteTitle).performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                biometricLogs.count { it.contains("biometric unlock request suppressed") } >
                    suppressedLogsBeforeClick
            }

            composeTestRule.runOnUiThread {
                pendingOptInCallback.getAndSet(null)?.invoke()
            }

            composeTestRule.waitForIdle()

            val noteDisplayed = runCatching {
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithText(noteContent).fetchSemanticsNodes().isNotEmpty()
                }
                true
            }.getOrDefault(false)

            if (noteDisplayed) {
                composeTestRule.onNodeWithText(noteContent).assertIsDisplayed()
            }

            return BiometricOptInFlowResult(
                launchedPrompts = launchedPrompts.toList(),
                biometricLogs = biometricLogs.toList(),
                noteDisplayed = noteDisplayed,
            )
        } finally {
            BiometricPromptTestHooks.interceptAuthenticate = null
            BiometricPromptTestHooks.logListener = null
            BiometricPromptTestHooks.disableOptInReplayGuard = false
        }
    }
}
