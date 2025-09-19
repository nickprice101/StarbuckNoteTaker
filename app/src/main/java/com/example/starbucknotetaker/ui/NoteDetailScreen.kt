package com.example.starbucknotetaker.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Patterns
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.Note
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun NoteDetailScreen(note: Note, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    var fullImage by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(note.title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            actions = {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
            }
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            val lines = remember(note.content) { note.content.lines() }
            lines.forEach { line ->
                val trimmed = line.trim()
                val imagePlaceholder = Regex("\\[\\[image:(\\d+)]]").matchEntire(trimmed)
                val filePlaceholder = Regex("\\[\\[file:(\\d+)]]").matchEntire(trimmed)
                val linkPlaceholder = Regex("\\[\\[link:(\\d+)]]").matchEntire(trimmed)
                when {
                    imagePlaceholder != null -> {
                        val index = imagePlaceholder.groupValues[1].toInt()
                        note.images.getOrNull(index)?.let { base64 ->
                            val bytes = remember(base64) { Base64.decode(base64, Base64.DEFAULT) }
                            val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clickable { fullImage = base64 }
                            )
                        }
                    }
                    filePlaceholder != null -> {
                        val index = filePlaceholder.groupValues[1].toInt()
                        note.files.getOrNull(index)?.let { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clickable {
                                        val bytes = Base64.decode(file.data, Base64.DEFAULT)
                                        val temp = File(context.cacheDir, file.name)
                                        temp.writeBytes(bytes)
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", temp)
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, file.mime)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.InsertDriveFile, contentDescription = file.name)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(file.name)
                            }
                        }
                    }
                    linkPlaceholder != null -> {
                        val index = linkPlaceholder.groupValues[1].toInt()
                        note.linkPreviews.getOrNull(index)?.let { preview ->
                            LinkPreviewCard(
                                preview = preview,
                                awaitingCompletion = false,
                                isLoading = false,
                                errorMessage = null,
                                onOpen = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preview.url)))
                                    }
                                }
                            )
                        }
                    }
                    else -> {
                        val annotated = buildAnnotatedString {
                            var lastIndex = 0
                            val matcher = Patterns.WEB_URL.matcher(trimmed)
                            while (matcher.find()) {
                                    val start = matcher.start()
                                    val end = matcher.end()
                                    append(trimmed.substring(lastIndex, start))
                                    val url = trimmed.substring(start, end)
                                    pushStringAnnotation(tag = "URL", annotation = url)
                                    pushStyle(SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline))
                                    append(url)
                                    pop()
                                    pop()
                                    lastIndex = end
                                }
                                append(trimmed.substring(lastIndex))
                            }
                            ClickableText(
                                text = annotated,
                                onClick = { offset ->
                                    annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { sa ->
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sa.item))
                                        context.startActivity(intent)
                                    }
                                },
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                    }
                }
            }
        }
        fullImage?.let { img ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                val bytes = remember(img) { Base64.decode(img, Base64.DEFAULT) }
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { fullImage = null },
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
                var menuExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(onClick = {
                            val bytesToSave = Base64.decode(img, Base64.DEFAULT)
                            val name = "note_image_${System.currentTimeMillis()}.png"
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            }
                            val uri = context.contentResolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                values
                            )
                            uri?.let {
                                context.contentResolver.openOutputStream(it)?.use { out ->
                                    out.write(bytesToSave)
                                }
                                Toast.makeText(context, "Image saved", Toast.LENGTH_SHORT).show()
                            }
                            menuExpanded = false
                        }) {
                            Text("Save image")
                        }
                    }
                }
            }
        }
    }
}
