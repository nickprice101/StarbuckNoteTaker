package com.example.starbucknotetaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.starbucknotetaker.AgentTurnUpdate
import com.example.starbucknotetaker.ConversationMemoryStore
import com.example.starbucknotetaker.Note
import com.example.starbucknotetaker.NoteAiAgent
import java.util.UUID
import kotlinx.coroutines.launch

internal enum class ChatAuthor { USER, AGENT, STATUS }

internal data class ChatMessageUi(
    val author: ChatAuthor,
    val text: String,
    val isError: Boolean = false,
    val isThinking: Boolean = false,
)

/** Full-screen, private chat surface backed by a multi-turn Google ADK session. */
@Composable
internal fun AgentConversationDialog(
    noteContext: String,
    onDismiss: () -> Unit,
    onInsertIntoNote: (String) -> Unit,
    memoryNoteId: Long? = null,
    persistMemory: Boolean = true,
    relatedNotes: List<Note> = emptyList(),
) {
    val context = LocalContext.current
    val memoryStore = remember { ConversationMemoryStore(context.applicationContext) }
    val conversation = remember(noteContext, memoryNoteId, persistMemory, relatedNotes) {
        NoteAiAgent.conversation(
            context = context.applicationContext,
            sessionId = UUID.randomUUID().toString(),
            noteContext = noteContext,
            relatedNotes = relatedNotes.filterNot { it.id == memoryNoteId },
            initialMemory = memoryNoteId
                ?.let { memoryStore.get(it, persistMemory) }
                .orEmpty(),
            onMemoryUpdated = { memory ->
                memoryNoteId?.let { memoryStore.put(it, memory, persistMemory) }
            },
        )
    }
    val messages = remember {
        mutableStateListOf(
            ChatMessageUi(
                author = ChatAuthor.STATUS,
                text = "Ask about this note, explore an idea, or draft something new.",
            ),
        )
    }
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun send() {
        val outgoing = input.trim()
        if (outgoing.isBlank() || isSending) return
        input = ""
        messages += ChatMessageUi(ChatAuthor.USER, outgoing)
        messages += ChatMessageUi(ChatAuthor.AGENT, "", isThinking = true)
        val replyIndex = messages.lastIndex
        isSending = true
        scope.launch {
            runCatching {
                conversation.send(outgoing).collect { update ->
                    when (update) {
                        is AgentTurnUpdate.Partial -> {
                            messages[replyIndex] = ChatMessageUi(
                                ChatAuthor.AGENT,
                                update.text,
                                isThinking = true,
                            )
                        }
                        is AgentTurnUpdate.Complete -> {
                            messages[replyIndex] = ChatMessageUi(ChatAuthor.AGENT, update.text)
                        }
                    }
                }
            }.onFailure { failure ->
                messages[replyIndex] = ChatMessageUi(
                    author = ChatAuthor.AGENT,
                    text = failure.message ?: "The on-device agent could not reply.",
                    isError = true,
                )
            }
            isSending = false
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("agentConversation"),
            color = MaterialTheme.colors.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Chat")
                            Text(
                                "On-device AI + web research",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onPrimary.copy(alpha = 0.75f),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close chat")
                        }
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colors.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colors.primary,
                    )
                    Text(
                        "Your note stays on this device. Questions that need current or unfamiliar facts can use web research.",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.caption,
                    )
                }
                Divider()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(messages) { message ->
                        ConversationBubble(
                            message = message,
                            showProgress = message.isThinking && message.text.isBlank(),
                            onInsert = if (
                                message.author == ChatAuthor.AGENT &&
                                message.text.isNotBlank() &&
                                !message.isError &&
                                !message.isThinking
                            ) {
                                { onInsertIntoNote(message.text) }
                            } else {
                                null
                            },
                        )
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("agentMessageInput"),
                        placeholder = { Text("Message the agent") },
                        enabled = !isSending,
                        maxLines = 5,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = { send() },
                        ),
                    )
                    IconButton(
                        onClick = { send() },
                        enabled = input.isNotBlank() && !isSending,
                        modifier = Modifier.testTag("sendAgentMessage"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationBubble(
    message: ChatMessageUi,
    showProgress: Boolean,
    onInsert: (() -> Unit)?,
) {
    if (message.author == ChatAuthor.STATUS) {
        Text(
            text = message.text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            style = MaterialTheme.typography.caption,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.65f),
        )
        return
    }
    val isUser = message.author == ChatAuthor.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 340.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            color = when {
                message.isError -> MaterialTheme.colors.error.copy(alpha = 0.12f)
                isUser -> MaterialTheme.colors.primary
                else -> MaterialTheme.colors.surface
            },
            elevation = if (isUser) 0.dp else 1.dp,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (showProgress) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    MarkdownCitationText(
                        markdown = message.text,
                        color = when {
                            message.isError -> MaterialTheme.colors.error
                            isUser -> MaterialTheme.colors.onPrimary
                            message.isThinking -> Color.LightGray
                            else -> MaterialTheme.colors.onSurface
                        },
                    )
                }
                if (onInsert != null) {
                    TextButton(
                        onClick = onInsert,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Add to note")
                    }
                }
            }
        }
    }
}
