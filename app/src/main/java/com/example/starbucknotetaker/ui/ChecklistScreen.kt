package com.example.starbucknotetaker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.animateItemPlacement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay

@Composable
fun AddChecklistScreen(
    onSave: (String?, List<ChecklistItem>) -> Unit,
    onBack: () -> Unit,
) {
    ChecklistEditorScreen(
        topBarTitle = "New Checklist",
        confirmContentDescription = "Save checklist",
        initialTitle = "",
        initialItems = emptyList(),
        onBack = onBack,
        onSave = onSave,
    )
}

@Composable
fun EditChecklistScreen(
    note: Note,
    onSave: (String?, List<ChecklistItem>) -> Unit,
    onCancel: () -> Unit,
) {
    ChecklistEditorScreen(
        topBarTitle = "Edit Checklist",
        confirmContentDescription = "Save changes",
        initialTitle = note.title,
        initialItems = note.checklistItems.orEmpty(),
        onBack = onCancel,
        onSave = onSave,
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
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val itemSpacingPx = with(density) { 4.dp.toPx() }
    var pendingFocusId by remember { mutableStateOf<Long?>(null) }
    var pendingScrollId by remember { mutableStateOf<Long?>(null) }
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
            if (nextItem.isChecked != items[currentIndex].isChecked) {
                break
            }
            val nextHeight = itemHeights[nextItem.id] ?: break
            val dragThreshold = (nextHeight.toFloat() + itemSpacingPx) / 2f
            val dragStep = nextHeight.toFloat() + itemSpacingPx
            if (dragOffset > dragThreshold) {
                items.move(currentIndex, currentIndex + 1)
                dragOffset -= dragStep
                dragTranslation -= dragStep
                currentIndex += 1
            } else {
                break
            }
        }
        while (currentIndex > 0) {
            val previousItem = items[currentIndex - 1]
            if (previousItem.isChecked != items[currentIndex].isChecked) {
                break
            }
            val previousHeight = itemHeights[previousItem.id] ?: break
            val dragThreshold = (previousHeight.toFloat() + itemSpacingPx) / 2f
            val dragStep = previousHeight.toFloat() + itemSpacingPx
            if (dragOffset < -dragThreshold) {
                items.move(currentIndex, currentIndex - 1)
                dragOffset += dragStep
                dragTranslation += dragStep
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

    fun normalizeCheckedItems() {
        val reordered = items.filterNot { it.isChecked } + items.filter { it.isChecked }
        if (reordered.map { it.id } != items.map { it.id }) {
            items.clear()
            items.addAll(reordered)
        }
    }

    fun addItemBelow(anchorId: Long?, initialText: String) {
        val newItem = EditableChecklistItem(
            id = nextId(),
            text = initialText,
            isChecked = false,
        )
        val insertIndex = anchorId?.let { targetId ->
            items.indexOfFirst { it.id == targetId }
                .takeIf { it != -1 }
                ?.plus(1)
        } ?: items.size
        items.add(insertIndex, newItem)
        pendingFocusId = newItem.id
        pendingScrollId = newItem.id
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

    LaunchedEffect(pendingScrollId, items.size) {
        val targetId = pendingScrollId ?: return@LaunchedEffect
        val targetIndex = items.indexOfFirst { it.id == targetId }
        if (targetIndex != -1) {
            listState.animateScrollToItem(targetIndex)
        }
        pendingScrollId = null
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
                    addItemBelow(anchorId = null, initialText = "")
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
                    .fillMaxSize()
                    .imePadding(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                userScrollEnabled = draggingItemId == null
            ) {
                items(items, key = { it.id }) { item ->
                    val isDragging = draggingItemId == item.id
                    ChecklistItemRow(
                        item = item,
                        requestFocus = pendingFocusId == item.id,
                        onFocusHandled = { pendingFocusId = null },
                        modifier = Modifier
                            .animateItemPlacement()
                            .onSizeChanged { size -> itemHeights[item.id] = size.height }
                            .graphicsLayer {
                                if (isDragging) {
                                    translationY = dragTranslation
                                    shadowElevation = 12.dp.toPx()
                                    scaleX = 1.03f
                                    scaleY = 1.03f
                                } else {
                                    translationY = 0f
                                    shadowElevation = 0f
                                    scaleX = 1f
                                    scaleY = 1f
                                }
                            }
                            .zIndex(if (isDragging) 1f else 0f),
                        dragHandleModifier = Modifier
                            .padding(start = 4.dp)
                            .size(28.dp)
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
                            addItemBelow(anchorId = item.id, initialText = initialText)
                        },
                        onCheckedChange = { checked ->
                            updateItem(items, item.id) { it.copy(isChecked = checked) }
                            normalizeCheckedItems()
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

@OptIn(ExperimentalFoundationApi::class)
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
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            delay(150)
            bringIntoViewRequester.bringIntoView()
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
                .bringIntoViewRequester(bringIntoViewRequester)
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
