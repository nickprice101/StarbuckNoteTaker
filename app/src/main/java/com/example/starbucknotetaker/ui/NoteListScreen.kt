package com.example.starbucknotetaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.material.FractionalThreshold
import androidx.compose.material.rememberSwipeableState
import androidx.compose.material.swipeable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.Note
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
@Composable
fun NoteListScreen(
    notes: List<Note>,
    onAddNote: () -> Unit,
    onOpenNote: (Int) -> Unit,
    onDeleteNote: (Int) -> Unit,
    onSettings: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = notes.filter {
        it.title.contains(query, true) ||
                it.content.contains(query, true) ||
                it.summary.contains(query, true)
    }
    var openIndex by remember { mutableStateOf<Int?>(null) }
    val focusManager = LocalFocusManager.current

    DisposableEffect(Unit) {
        onDispose { focusManager.clearFocus(force = true) }
    }
    Scaffold(
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FloatingActionButton(
                    onClick = onSettings,
                    backgroundColor = MaterialTheme.colors.primary
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White
                    )
                }
                FloatingActionButton(
                    onClick = onAddNote,
                    backgroundColor = MaterialTheme.colors.primary
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.NoteAdd,
                        contentDescription = "Add note",
                        tint = Color.White
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(filtered, key = { _, note -> note.id }) { index, note ->
                    val originalIndex = notes.indexOf(note)
                    val showDate = index == 0 || !isSameDay(filtered[index - 1].date, note.date)
                    if (showDate) {
                        DateHeader(note.date)
                    }
                    SwipeToDeleteNoteItem(
                        note = note,
                        isOpen = openIndex == originalIndex,
                        onOpen = { openIndex = originalIndex },
                        onClose = { if (openIndex == originalIndex) openIndex = null },
                        onClick = { onOpenNote(originalIndex) },
                        onDelete = {
                            onDeleteNote(originalIndex)
                            if (openIndex == originalIndex) openIndex = null
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun SwipeToDeleteNoteItem(
    note: Note,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val actionWidth = 80.dp
    val actionWidthPx = with(LocalDensity.current) { actionWidth.toPx() }
    val swipeState = rememberSwipeableState(0)
    val scope = rememberCoroutineScope()

    LaunchedEffect(isOpen) {
        if (isOpen) {
            swipeState.animateTo(1)
        } else {
            swipeState.animateTo(0)
        }
    }

    LaunchedEffect(swipeState.currentValue) {
        if (swipeState.currentValue == 1) {
            onOpen()
        } else {
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .swipeable(
                state = swipeState,
                anchors = mapOf(
                    0f to 0,
                    -actionWidthPx to 1
                ),
                thresholds = { _, _ -> FractionalThreshold(0.3f) },
                orientation = Orientation.Horizontal
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(actionWidth)
                .align(Alignment.CenterEnd)
                .background(Color.Red),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = {
                onDelete()
                scope.launch { swipeState.snapTo(0) }
            }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.White
                )
            }
        }
        NoteListItem(
            note = note,
            onClick = {
                if (swipeState.currentValue == 1) {
                    scope.launch { swipeState.animateTo(0) }
                } else {
                    onClick()
                }
            },
            modifier = Modifier.offset { IntOffset(swipeState.offset.value.roundToInt(), 0) }
        )
    }
}

@Composable
fun NoteListItem(note: Note, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Text(
            text = note.title,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = note.summary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun DateHeader(date: Long) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = formatter.format(Date(date)), fontWeight = FontWeight.Light)
        Divider(
            modifier = Modifier
                .padding(start = 8.dp)
                .height(1.dp)
                .weight(1f)
        )
    }
}

private fun isSameDay(first: Long, second: Long): Boolean {
    val formatter = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return formatter.format(Date(first)) == formatter.format(Date(second))
}
