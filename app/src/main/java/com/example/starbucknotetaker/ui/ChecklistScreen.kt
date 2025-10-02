package com.example.starbucknotetaker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.ChecklistItem
import com.example.starbucknotetaker.Note
import com.example.starbucknotetaker.Summarizer

@Composable
fun AddChecklistScreen(
    onSave: (String?, List<ChecklistItem>) -> Unit,
    onBack: () -> Unit,
    summarizerState: Summarizer.SummarizerState,
) {
    ChecklistEditorScreen(
        topBarTitle = "New Checklist",
        confirmContentDescription = "Save checklist",
        initialTitle = "",
        initialItems = emptyList(),
        onBack = onBack,
        onSave = onSave,
        summarizerState = summarizerState,
    )
}

@Composable
fun EditChecklistScreen(
    note: Note,
    onSave: (String?, List<ChecklistItem>) -> Unit,
    onCancel: () -> Unit,
    summarizerState: Summarizer.SummarizerState,
) {
    ChecklistEditorScreen(
        topBarTitle = "Edit Checklist",
        confirmContentDescription = "Save changes",
        initialTitle = note.title,
        initialItems = note.checklistItems.orEmpty(),
        onBack = onCancel,
        onSave = onSave,
        summarizerState = summarizerState,
    )
}

@Composable
private fun ChecklistEditorScreen(
    topBarTitle: String,
    confirmContentDescription: String,
    initialTitle: String,
    initialItems: List<ChecklistItem>,
    onBack: () -> Unit,
    onSave: (String?, List<ChecklistItem>) -> Unit,
    summarizerState: Summarizer.SummarizerState,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var nextItemId by remember(initialItems) { mutableStateOf(initialItems.size.toLong()) }
    val items = remember(initialItems) {
        mutableStateListOf<EditableChecklistItem>().apply {
            initialItems.forEachIndexed { index, item ->
                add(
                    EditableChecklistItem(
                        id = index.toLong(),
                        text = item.text,
                        isChecked = item.isChecked,
                    )
                )
            }
            if (isEmpty()) {
                add(EditableChecklistItem(id = 0L, text = "", isChecked = false))
                nextItemId = 1L
            }
        }
    }
    val focusManager = LocalFocusManager.current
    val hideKeyboard = rememberKeyboardHider()
    val context = LocalContext.current
    var pendingFocusId by remember { mutableStateOf<Long?>(null) }

    fun nextId(): Long {
        val id = nextItemId
        nextItemId += 1
        return id
    }

    fun ensureNonEmpty() {
        if (items.isEmpty()) {
            items.add(EditableChecklistItem(id = nextId(), text = "", isChecked = false))
        }
    }

    fun handleSave() {
        val sanitized = items
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() }
            .map { ChecklistItem(text = it.text, isChecked = it.isChecked) }
        if (sanitized.isEmpty()) {
            Toast.makeText(context, "Add at least one checklist item", Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        focusManager.clearFocus(force = true)
        onSave(title.takeIf { it.isNotBlank() }, sanitized)
    }

    LaunchedEffect(items.size) {
        if (items.isEmpty()) {
            ensureNonEmpty()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topBarTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hideKeyboard()
                            focusManager.clearFocus(force = true)
                            onBack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { handleSave() }) {
                        Icon(Icons.Default.Check, contentDescription = confirmContentDescription)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val last = items.lastOrNull()
                    if (last != null && last.text.isBlank()) {
                        pendingFocusId = last.id
                    } else {
                        val newItem = EditableChecklistItem(id = nextId(), text = "", isChecked = false)
                        items.add(newItem)
                        pendingFocusId = newItem.id
                    }
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add checklist item")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            SummarizerStatusBanner(state = summarizerState)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    ChecklistItemRow(
                        item = item,
                        requestFocus = pendingFocusId == item.id,
                        onFocusHandled = { pendingFocusId = null },
                        onTextChange = { text ->
                            items[index] = item.copy(text = text)
                        },
                        onAddBelow = { initialText ->
                            val newItem = EditableChecklistItem(
                                id = nextId(),
                                text = initialText,
                                isChecked = false,
                            )
                            items.add(index + 1, newItem)
                            pendingFocusId = newItem.id
                        },
                        onCheckedChange = { checked ->
                            items[index] = item.copy(isChecked = checked)
                        },
                        onRemove = {
                            if (items.size == 1) {
                                items[0] = items[0].copy(text = "", isChecked = false)
                            } else {
                                items.removeAt(index)
                                pendingFocusId = items.getOrNull((index - 1).coerceAtLeast(0))?.id
                            }
                            ensureNonEmpty()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistItemRow(
    item: EditableChecklistItem,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onTextChange: (String) -> Unit,
    onAddBelow: (String) -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            onFocusHandled()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = { onCheckedChange(!item.isChecked) }) {
            if (item.isChecked) {
                Icon(Icons.Default.TaskAlt, contentDescription = "Mark as incomplete")
            } else {
                Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "Mark as complete")
            }
        }
        OutlinedTextField(
            value = item.text,
            onValueChange = { value ->
                val segments = value.split('\n')
                if (segments.size > 1) {
                    onTextChange(segments.first())
                    segments.drop(1).forEach { segment ->
                        onAddBelow(segment)
                    }
                } else {
                    onTextChange(value)
                }
            },
            placeholder = { Text("Checklist item") },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onAddBelow("") })
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove item")
        }
    }
}

private data class EditableChecklistItem(
    val id: Long,
    val text: String,
    val isChecked: Boolean,
)
