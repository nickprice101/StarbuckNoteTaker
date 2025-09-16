package com.example.starbucknotetaker.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter

@Composable
fun AddNoteScreen(
    onSave: (String?, String, List<Pair<Uri, Int>>, List<Uri>) -> Unit,
    onBack: () -> Unit,
    onDisablePinCheck: () -> Unit,
    onEnablePinCheck: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    val blocks = remember { mutableStateListOf<NoteBlock>(NoteBlock.Text("")) }
    val context = LocalContext.current
    val hideKeyboard = rememberKeyboardHider()
    val focusManager = LocalFocusManager.current

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val last = blocks.lastOrNull()
            if (last is NoteBlock.Text && last.text.isBlank()) {
                blocks[blocks.size - 1] = NoteBlock.Image(it, 0)
                blocks.add(NoteBlock.Text(""))
            } else {
                blocks.add(NoteBlock.Image(it, 0))
                blocks.add(NoteBlock.Text(""))
            }
        }
        onEnablePinCheck()
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val last = blocks.lastOrNull()
            if (last is NoteBlock.Text && last.text.isBlank()) {
                blocks[blocks.size - 1] = NoteBlock.File(it)
                blocks.add(NoteBlock.Text(""))
            } else {
                blocks.add(NoteBlock.File(it))
                blocks.add(NoteBlock.Text(""))
            }
        }
        onEnablePinCheck()
    }

    DisposableEffect(Unit) {
        onDispose {
            onEnablePinCheck()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Note") },
                navigationIcon = {
                    IconButton(onClick = {
                        hideKeyboard()
                        focusManager.clearFocus(force = true)
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val imageList = mutableListOf<Pair<Uri, Int>>()
                        val fileList = mutableListOf<Uri>()
                        val content = buildString {
                            var imageIndex = 0
                            var fileIndex = 0
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
                                        imageList.add(block.uri to block.rotation)
                                        imageIndex++
                                    }
                                    is NoteBlock.File -> {
                                        append("[[file:")
                                        append(fileIndex)
                                        append("]]\n")
                                        fileList.add(block.uri)
                                        fileIndex++
                                    }
                                }
                            }
                        }.trim()
                        hideKeyboard()
                        focusManager.clearFocus(force = true)
                        onSave(title, content, imageList, fileList)
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
                            label = if (index == 0) {
                                { Text("Content") }
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }
                    is NoteBlock.Image -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            Image(
                                painter = rememberAsyncImagePainter(block.uri),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(rotationZ = block.rotation.toFloat())
                            )
                            IconButton(
                                onClick = {
                                    blocks[index] = block.copy(rotation = (block.rotation + 270) % 360)
                                },
                                modifier = Modifier.align(Alignment.BottomStart)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.RotateLeft,
                                    contentDescription = "Rotate",
                                )
                            }
                        }
                    }
                    is NoteBlock.File -> {
                        val name = remember(block.uri) {
                            var result = "File"
                            context.contentResolver.query(block.uri, null, null, null, null)?.use { c ->
                                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0 && c.moveToFirst()) result = c.getString(idx)
                            }
                            result
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(name)
                        }
                    }
                }
            }
            item {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.InsertDriveFile, contentDescription = "Add File")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add File")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onDisablePinCheck()
                            imageLauncher.launch(arrayOf("image/*"))
                        }) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Image")
                        }
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onDisablePinCheck()
                            fileLauncher.launch(arrayOf("*/*"))
                        }) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add File")
                        }
                    }
                }
            }
        }
    }
}

private sealed class NoteBlock {
    data class Text(val text: String) : NoteBlock()
    data class Image(val uri: Uri, val rotation: Int) : NoteBlock()
    data class File(val uri: Uri) : NoteBlock()
}

