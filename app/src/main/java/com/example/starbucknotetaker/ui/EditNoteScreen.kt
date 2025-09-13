package com.example.starbucknotetaker.ui

import android.content.Intent
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import com.example.starbucknotetaker.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    val last = lastOrNull()
                    if (last is EditBlock.Text) {
                        val lastIndex = size - 1
                        val newText = if (last.text.isEmpty()) line else last.text + "\n" + line
                        this[lastIndex] = EditBlock.Text(newText)
                    } else {
                        add(EditBlock.Text(line))
                    }
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
            scope.launch {
                val base64 = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        val bytes = input.readBytes()
                        val exif = ExifInterface(ByteArrayInputStream(bytes))
                        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        val exifRotation = when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (exifRotation != 0) {
                            val matrix = Matrix().apply { postRotate(exifRotation.toFloat()) }
                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        }
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
                    }
                }
                base64?.let { data ->
                    val last = blocks.lastOrNull()
                    if (last is EditBlock.Text && last.text.isBlank()) {
                        blocks[blocks.size - 1] = EditBlock.Image(data)
                        blocks.add(EditBlock.Text(""))
                    } else {
                        blocks.add(EditBlock.Image(data))
                        blocks.add(EditBlock.Text(""))
                    }
                }
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
                        var bitmap by remember(block.data) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(block.data) {
                            bitmap = withContext(Dispatchers.IO) {
                                val bytes = Base64.decode(block.data, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            bitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            val (rotated, encoded) = withContext(Dispatchers.IO) {
                                                val matrix = Matrix().apply { postRotate(270f) }
                                                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                                                val baos = ByteArrayOutputStream()
                                                r.compress(Bitmap.CompressFormat.PNG, 100, baos)
                                                r to Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
                                            }
                                            bitmap = rotated
                                            blocks[index] = EditBlock.Image(encoded)
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.BottomStart)
                                ) {
                                    Icon(Icons.Default.RotateLeft, contentDescription = "Rotate", tint = Color.White)
                                }
                            }
                            IconButton(
                                onClick = {
                                    blocks.removeAt(index)
                                    val prevIndex = index - 1
                                    if (prevIndex >= 0 && prevIndex < blocks.size) {
                                        val prev = blocks[prevIndex]
                                        val next = blocks.getOrNull(prevIndex + 1)
                                        if (prev is EditBlock.Text && next is EditBlock.Text) {
                                            blocks[prevIndex] = EditBlock.Text(prev.text + "\n" + next.text)
                                            blocks.removeAt(prevIndex + 1)
                                        }
                                    }
                                },
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

