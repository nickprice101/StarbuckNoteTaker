package com.example.starbucknotetaker.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.starbucknotetaker.RewriteDestination
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiAssistantDialogInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reformatButtonOffersBothDestinationsAndCanEditCurrentNote() {
        var selectedDestination: RewriteDestination? = null
        composeRule.setContent {
            MaterialTheme {
                AiAssistantDialog(
                    onDismiss = {},
                    onAskQuestion = {},
                    onReformat = { selectedDestination = it },
                )
            }
        }

        composeRule.onNodeWithText("Reformat note").assertExists().performClick()
        composeRule.onNodeWithText("Create new note").assertExists()
        composeRule.onNodeWithText("Edit current note").assertExists().performClick()

        composeRule.runOnIdle {
            assertEquals(RewriteDestination.CURRENT_NOTE, selectedDestination)
        }
    }

    @Test
    fun reformatButtonCanCreateNewNote() {
        var selectedDestination: RewriteDestination? = null
        composeRule.setContent {
            MaterialTheme {
                AiAssistantDialog(
                    onDismiss = {},
                    onAskQuestion = {},
                    onReformat = { selectedDestination = it },
                )
            }
        }

        composeRule.onNodeWithText("Reformat note").performClick()
        composeRule.onNodeWithText("Create new note").performClick()

        composeRule.runOnIdle {
            assertEquals(RewriteDestination.NEW_NOTE, selectedDestination)
        }
    }
}
