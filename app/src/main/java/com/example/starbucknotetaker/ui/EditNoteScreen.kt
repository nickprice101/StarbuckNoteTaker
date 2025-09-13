package com.example.starbucknotetaker.ui

import android.content.Intent
import android.util.Base64
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.example.starbucknotetaker.Note
import kotlinx.coroutines.launch

@Composable
fun EditNoteScreen(
    note: Note,
    onSave: (String?, String, List<String>) -> Unit,
    onCancel: () -> Unit,
    onDisablePinCheck: () -> Unit,
    onEnablePinCheck: () -> Unit
) {
    var title by remember { mutableStateOf(note.title) }
    val blocks = remember {
        mutableStateListOf<EditBlock>().apply {
            val lines = note.content.lines()
            lines.forEach { line ->
                val placeholder = Regex("\\[\\[image:(\\d+)]]").matchEntire(line.trim())
                if (placeholder != null) {
                    val idx = placeholder.groupValues[1].toInt()
                    note.images.getOrNull(idx)?.let { data ->
                        add(EditBlock.Image(data))
                        add(EditBlock.Text(""))
                    }
                } else {
                    add(EditBlock.Text(line))
                }
            }
            if (isEmpty()) add(EditBlock.Text(""))
        }
    }
    val context = LocalContext.current
    val scaffoldState = rememberScaffoldState()
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        onEnablePinCheck()
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            context.contentResolver.openInputStream(it)?.use { input ->
                val bytes = input.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                blocks.add(EditBlock.Image(base64))
                blocks.add(EditBlock.Text(""))
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { onEnablePinCheck() }
    }

    Scaffold(
        scaffoldState = scaffoldState,
        topBar = {
            TopAppBar(
                title = { Text("Edit Note") },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            scaffoldState.snackbarHostState.showSnackbar(
                                "Changes discarded",
                                duration = SnackbarDuration.Short
                            )
                            onCancel()
                        }
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Discard")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val images = mutableListOf<String>()
                        val content = buildString {
                            blocks.forEach { block ->
                                when (block) {
                                    is EditBlock.Text -> {
                                        append(block.text)
                                        append("\n")
                                    }
                                    is EditBlock.Image -> {
                                        append("[[image:")
                                        append(images.size)
                                        append("]]\n")
                                        images.add(block.data)
                                    }
                                }
                            }
                        }.trim()
                        scope.launch {
                            onSave(title, content, images)
                            scaffoldState.snackbarHostState.showSnackbar(
                                "Changes saved",
                                duration = SnackbarDuration.Short
                            )
                            onCancel()
                        }
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
                    is EditBlock.Text -> {
                        OutlinedTextField(
                            value = block.text,
                            onValueChange = { newText -> blocks[index] = block.copy(text = newText) },
                            label = { if (index == 0) Text("Content") else null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }
                    is EditBlock.Image -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            val bytes = remember(block.data) { Base64.decode(block.data, Base64.DEFAULT) }
                            val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = { blocks.removeAt(index) },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White)
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        onDisablePinCheck()
                        launcher.launch(arrayOf("image/*"))
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Add Image")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Image")
                }
            }
        }
    }
}

private sealed class EditBlock {
    data class Text(val text: String) : EditBlock()
    data class Image(val data: String) : EditBlock()
}

