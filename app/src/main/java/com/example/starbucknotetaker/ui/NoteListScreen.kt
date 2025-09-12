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
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    onDeleteNote: (Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, notes) {
        notes.filter {
            it.title.contains(query, true) || it.content.contains(query, true)
        }
    }
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddNote,
                backgroundColor = Color(0xFF4CAF50)
            ) {
                Icon(Icons.Default.NoteAdd, contentDescription = "Add note", tint = Color.White)
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
                    SwipeToDeleteNoteItem(
                        note = note,
                        showDate = showDate,
                        onClick = { onOpenNote(originalIndex) },
                        onDelete = { onDeleteNote(originalIndex) }
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
    showDate: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val actionWidth = 80.dp
    val actionWidthPx = with(LocalDensity.current) { actionWidth.toPx() }
    val swipeState = rememberSwipeableState(0)
    val scope = rememberCoroutineScope()

    LaunchedEffect(swipeState.currentValue) {
        if (swipeState.currentValue == 2) {
            onDelete()
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
                    -actionWidthPx to 1,
                    -actionWidthPx * 2 to 2
                ),
                thresholds = { _, _ -> FractionalThreshold(0.3f) },
                orientation = Orientation.Horizontal
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Red),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.width(actionWidth)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
            }
        }
        NoteListItem(
            note = note,
            showDate = showDate,
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
fun NoteListItem(note: Note, showDate: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        if (showDate) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = formatter.format(Date(note.date)), fontWeight = FontWeight.Light)
                Divider(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .height(1.dp)
                        .weight(1f)
                )
            }
        }
        Text(
            text = note.title,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = note.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

private fun isSameDay(first: Long, second: Long): Boolean {
    val formatter = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return formatter.format(Date(first)) == formatter.format(Date(second))
}
