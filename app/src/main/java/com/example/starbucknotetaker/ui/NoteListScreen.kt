package com.example.starbucknotetaker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.Note
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun NoteListScreen(
    notes: List<Note>,
    onAddNote: () -> Unit,
    onOpenNote: (Int) -> Unit
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
        Column(modifier = Modifier
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
                itemsIndexed(filtered) { _, note ->
                    val originalIndex = notes.indexOf(note)
                    NoteListItem(note = note, onClick = { onOpenNote(originalIndex) })
                }
            }
        }
    }
}

@Composable
fun NoteListItem(note: Note, onClick: () -> Unit) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    Column(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = formatter.format(java.util.Date(note.date)), fontWeight = FontWeight.Light)
            Divider(modifier = Modifier
                .padding(start = 8.dp)
                .height(1.dp)
                .weight(1f))
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
