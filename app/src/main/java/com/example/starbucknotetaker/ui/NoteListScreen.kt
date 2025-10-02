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
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.ChecklistItem
import com.example.starbucknotetaker.Note
import com.example.starbucknotetaker.NoteEvent
import com.example.starbucknotetaker.Summarizer
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
@Composable
fun NoteListScreen(
    notes: List<Note>,
    onAddNote: () -> Unit,
    onAddChecklist: () -> Unit,
    onAddEvent: () -> Unit,
    onOpenNote: (Note) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onSettings: () -> Unit,
    summarizerState: Summarizer.SummarizerState
) {
    var query by remember { mutableStateOf("") }
    val filtered = notes.filter {
        it.title.contains(query, true) ||
                it.content.contains(query, true) ||
                it.summary.contains(query, true) ||
                (it.event?.location?.contains(query, true) ?: false)
    }
    var openNoteId by remember { mutableStateOf<Long?>(null) }
    val focusManager = LocalFocusManager.current
    val hideKeyboard = rememberKeyboardHider()
    var creationMenuExpanded by remember { mutableStateOf(false) }
    Scaffold(
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        hideKeyboard()
                        focusManager.clearFocus(force = true)
                        onSettings()
                    },
                    backgroundColor = MaterialTheme.colors.primary
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White
                    )
                }
                Box {
                    FloatingActionButton(
                        onClick = {
                            hideKeyboard()
                            focusManager.clearFocus(force = true)
                            creationMenuExpanded = true
                        },
                        backgroundColor = MaterialTheme.colors.primary
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.NoteAdd,
                            contentDescription = "Add note",
                            tint = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = creationMenuExpanded,
                        onDismissRequest = { creationMenuExpanded = false }
                    ) {
                        DropdownMenuItem(onClick = {
                            creationMenuExpanded = false
                            onAddNote()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.NoteAdd,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New Note")
                        }
                        DropdownMenuItem(onClick = {
                            creationMenuExpanded = false
                            onAddChecklist()
                        }) {
                            Icon(Icons.Default.ViewList, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New Checklist")
                        }
                        DropdownMenuItem(onClick = {
                            creationMenuExpanded = false
                            onAddEvent()
                        }) {
                            Icon(Icons.Default.Event, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New Event")
                        }
                    }
                }
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
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(filtered, key = { _, note -> note.id }) { index, note ->
                    val showDate = index == 0 || !isSameDay(filtered[index - 1].date, note.date)
                    if (showDate) {
                        DateHeader(note.date)
                    }
                    SwipeToDeleteNoteItem(
                        note = note,
                        isOpen = openNoteId == note.id,
                        onOpen = { openNoteId = note.id },
                        onClose = { if (openNoteId == note.id) openNoteId = null },
                        onClick = {
                            hideKeyboard()
                            focusManager.clearFocus(force = true)
                            onOpenNote(note)
                        },
                        onDelete = {
                            onDeleteNote(note.id)
                            if (openNoteId == note.id) openNoteId = null
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
        note.event?.let { event ->
            Text(
                text = formatEventRange(event),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            event.location?.takeIf { it.isNotBlank() }?.let { location ->
                val locationDisplay = rememberEventLocationDisplay(location)
                Text(
                    text = locationDisplay?.name ?: location,
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondary = locationDisplay?.address
                    ?.takeIf { !it.equals(locationDisplay.name, ignoreCase = true) }
                secondary?.let { address ->
                    Text(
                        text = address,
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(top = 1.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        when {
            note.checklistItems != null -> {
                if (note.isLocked) {
                    LockedSummaryPlaceholder(modifier = Modifier.padding(top = 2.dp))
                } else {
                    ChecklistPreview(items = note.checklistItems, modifier = Modifier.padding(top = 2.dp))
                }
            }
            note.summary.isNotBlank() -> {
                if (note.isLocked) {
                    LockedSummaryPlaceholder(modifier = Modifier.padding(top = 2.dp))
                } else {
                    Text(
                        text = note.summary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistPreview(items: List<ChecklistItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.take(3).forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (item.isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = MaterialTheme.colors.primary.copy(alpha = if (item.isChecked) 0.9f else 0.6f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = item.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.body2,
                    color = if (item.isChecked) MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    else MaterialTheme.colors.onSurface,
                    textDecoration = if (item.isChecked) TextDecoration.LineThrough else null,
                )
            }
        }
        if (items.size > 3) {
            Text(
                text = "+${items.size - 3} more",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun LockedSummaryPlaceholder(modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colors.onSurface.copy(alpha = 0.15f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(8.dp)
                    .background(barColor, shape = RoundedCornerShape(4.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(8.dp)
                    .background(barColor, shape = RoundedCornerShape(4.dp))
            )
        }
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

private val eventDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
private val eventTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatEventRange(event: NoteEvent): String {
    val zoneId = runCatching { ZoneId.of(event.timeZone) }.getOrDefault(ZoneId.systemDefault())
    val start = Instant.ofEpochMilli(event.start).atZone(zoneId)
    val end = Instant.ofEpochMilli(event.end).atZone(zoneId)
    val zoneCode = formatZoneCode(zoneId, Locale.getDefault(), start.toInstant())
    return if (event.allDay) {
        val startDate = start.toLocalDate()
        val endDateExclusive = end.toLocalDate()
        val lastDate = endDateExclusive.minusDays(1)
        if (lastDate.isBefore(startDate) || lastDate.isEqual(startDate)) {
            "All-day • $zoneCode"
        } else {
            "All-day • Ends ${eventDayFormatter.format(lastDate)} • $zoneCode"
        }
    } else {
        val sameDay = start.toLocalDate() == end.toLocalDate()
        val timePortion = if (sameDay) {
            "${eventTimeFormatter.format(start)} – ${eventTimeFormatter.format(end)}"
        } else {
            "${eventTimeFormatter.format(start)} – ${eventDayFormatter.format(end)} ${eventTimeFormatter.format(end)}"
        }
        "$timePortion • $zoneCode"
    }
}
