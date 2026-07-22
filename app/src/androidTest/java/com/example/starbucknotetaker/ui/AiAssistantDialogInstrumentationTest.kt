package com.example.starbucknotetaker.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.starbucknotetaker.RewriteDestination
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiAssistantDialogInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reformatDialogOffersBothDestinationsAndCanUpdateCurrentNote() {
        var selectedDestination: RewriteDestination? = null
        composeRule.setContent {
            MaterialTheme {
                ReformatDestinationDialog(
                    onDismiss = {},
                    onReformat = { selectedDestination = it },
                )
            }
        }

        composeRule.onNodeWithText("Reformat note").assertExists()
        composeRule.onNodeWithText("Create new note").assertExists()
        composeRule.onNodeWithText("Update this note").assertExists().performClick()

        composeRule.runOnIdle {
            assertEquals(RewriteDestination.CURRENT_NOTE, selectedDestination)
        }
    }

    @Test
    fun reformatDialogCanCreateNewNote() {
        var selectedDestination: RewriteDestination? = null
        composeRule.setContent {
            MaterialTheme {
                ReformatDestinationDialog(
                    onDismiss = {},
                    onReformat = { selectedDestination = it },
                )
            }
        }

        composeRule.onNodeWithText("Create new note").performClick()

        composeRule.runOnIdle {
            assertEquals(RewriteDestination.NEW_NOTE, selectedDestination)
        }
    }

    @Test
    fun conversationUsesStandardPrivateChatSurface() {
        composeRule.setContent {
            MaterialTheme {
                AgentConversationDialog(
                    noteContext = "Project launch notes",
                    onDismiss = {},
                    onInsertIntoNote = {},
                )
            }
        }

        composeRule.onNodeWithTag("agentConversation").assertExists()
        composeRule.onNodeWithText("Chat").assertExists()
        composeRule.onNodeWithText("On-device AI + web research").assertExists()
        composeRule.onNodeWithTag("agentMessageInput").assertExists()
        composeRule.onNodeWithTag("sendAgentMessage").assertExists()
    }
}
