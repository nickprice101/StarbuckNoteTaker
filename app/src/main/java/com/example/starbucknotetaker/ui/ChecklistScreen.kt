package com.example.starbucknotetaker.ui

import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
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
    val itemHeights = remember(initialItems) { mutableStateMapOf<Long, Int>() }
    var draggingItemId by remember(initialItems) { mutableStateOf<Long?>(null) }
    var dragOffset by remember(initialItems) { mutableStateOf(0f) }
    var dragTranslation by remember(initialItems) { mutableStateOf(0f) }

    fun resetDrag() {
        draggingItemId = null
        dragOffset = 0f
        dragTranslation = 0f
    }

    fun handleDrag(delta: Float) {
        val id = draggingItemId ?: return
        var currentIndex = items.indexOfFirst { it.id == id }
        if (currentIndex == -1) {
            return
        }
        dragOffset += delta
        dragTranslation += delta
        while (currentIndex < items.lastIndex) {
            val nextItem = items[currentIndex + 1]
            val nextHeight = itemHeights[nextItem.id] ?: break
            if (dragOffset > nextHeight.toFloat() / 2f) {
                items.move(currentIndex, currentIndex + 1)
                dragOffset -= nextHeight.toFloat()
                dragTranslation -= nextHeight.toFloat()
                currentIndex += 1
            } else {
                break
            }
        }
        while (currentIndex > 0) {
            val previousItem = items[currentIndex - 1]
            val previousHeight = itemHeights[previousItem.id] ?: break
            if (dragOffset < -previousHeight.toFloat() / 2f) {
                items.move(currentIndex, currentIndex - 1)
                dragOffset += previousHeight.toFloat()
                dragTranslation += previousHeight.toFloat()
                currentIndex -= 1
            } else {
                break
            }
        }
    }

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
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = draggingItemId == null
            ) {
                items(items, key = { it.id }) { item ->
                    val isDragging = draggingItemId == item.id
                    ChecklistItemRow(
                        item = item,
                        requestFocus = pendingFocusId == item.id,
                        onFocusHandled = { pendingFocusId = null },
                        modifier = Modifier
                            .onSizeChanged { size -> itemHeights[item.id] = size.height }
                            .graphicsLayer {
                                if (isDragging) {
                                    translationY = dragTranslation
                                    shadowElevation = 8.dp.toPx()
                                } else {
                                    translationY = 0f
                                    shadowElevation = 0f
                                }
                            }
                            .zIndex(if (isDragging) 1f else 0f),
                        dragHandleModifier = Modifier
                            .padding(start = 4.dp)
                            .size(24.dp)
                            .pointerInput(item.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingItemId = item.id
                                        dragOffset = 0f
                                        dragTranslation = 0f
                                    },
                                    onDragEnd = { resetDrag() },
                                    onDragCancel = { resetDrag() },
                                    onDrag = { change, dragAmount ->
                                        @Suppress("DEPRECATION")
                                        change.consumeAllChanges()
                                        handleDrag(dragAmount.y)
                                    }
                                )
                            },
                        onTextChange = { text ->
                            updateItem(items, item.id) { it.copy(text = text) }
                        },
                        onAddBelow = { initialText ->
                            val newItem = EditableChecklistItem(
                                id = nextId(),
                                text = initialText,
                                isChecked = false,
                            )
                            val insertIndex = items.indexOfFirst { it.id == item.id }
                            if (insertIndex == -1) {
                                items.add(newItem)
                            } else {
                                items.add(insertIndex + 1, newItem)
                            }
                            pendingFocusId = newItem.id
                        },
                        onCheckedChange = { checked ->
                            updateItem(items, item.id) { it.copy(isChecked = checked) }
                        },
                        onRemove = {
                            val removeIndex = items.indexOfFirst { it.id == item.id }
                            if (removeIndex == -1) {
                                return@ChecklistItemRow
                            }
                            if (items.size == 1) {
                                items[0] = items[0].copy(text = "", isChecked = false)
                            } else {
                                items.removeAt(removeIndex)
                                val focusIndex = (removeIndex - 1).coerceAtLeast(0)
                                pendingFocusId = items.getOrNull(focusIndex)?.id
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
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier,
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
        modifier = modifier.fillMaxWidth()
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
        Box(
            modifier = dragHandleModifier,
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Reorder item"
            )
        }
    }
}

private fun <T> SnapshotStateList<T>.move(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex) return
    val item = removeAt(fromIndex)
    val targetIndex = toIndex.coerceIn(0, size)
    add(targetIndex, item)
}

private fun updateItem(
    list: SnapshotStateList<EditableChecklistItem>,
    id: Long,
    transform: (EditableChecklistItem) -> EditableChecklistItem,
) {
    val index = list.indexOfFirst { it.id == id }
    if (index != -1) {
        list[index] = transform(list[index])
    }
}

private data class EditableChecklistItem(
    val id: Long,
    val text: String,
    val isChecked: Boolean,
)
