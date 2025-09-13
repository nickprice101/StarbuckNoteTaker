package com.example.starbucknotetaker.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter

@Composable
fun AddNoteScreen(
    onSave: (String?, String, List<Uri>) -> Unit,
    onBack: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    val blocks = remember { mutableStateListOf<NoteBlock>(NoteBlock.Text("")) }
    val images = remember { mutableStateListOf<Uri>() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            images.add(it)
            blocks.add(NoteBlock.Image(it))
            blocks.add(NoteBlock.Text(""))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val content = buildString {
                            var imageIndex = 0
                            blocks.forEach { block ->
                                when (block) {
                                    is NoteBlock.Text -> {
                                        append(block.text)
                                        append("\n")
                                    }
                                    is NoteBlock.Image -> {
                                        append("[[image:")
                                        append(imageIndex)
                                        append("]]\n")
                                        imageIndex++
                                    }
                                }
                            }
                        }.trim()
                        onSave(title, content, images)
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }
            itemsIndexed(blocks) { index, block ->
                when (block) {
                    is NoteBlock.Text -> {
                        OutlinedTextField(
                            value = block.text,
                            onValueChange = { newText ->
                                blocks[index] = block.copy(text = newText)
                            },
                            label = { if (index == 0) Text("Content") else null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }
                    is NoteBlock.Image -> {
                        Image(
                            painter = rememberAsyncImagePainter(block.uri),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { launcher.launch("image/*") },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Add Image")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Image")
                }
            }
        }
    }
}

private sealed class NoteBlock {
    data class Text(val text: String) : NoteBlock()
    data class Image(val uri: Uri) : NoteBlock()
}

