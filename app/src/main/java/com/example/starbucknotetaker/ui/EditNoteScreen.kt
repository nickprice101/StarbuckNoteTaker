package com.example.starbucknotetaker.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.example.starbucknotetaker.Note
import com.example.starbucknotetaker.NoteFile
import com.example.starbucknotetaker.LinkPreviewFetcher
import com.example.starbucknotetaker.LinkPreviewResult
import com.example.starbucknotetaker.NoteLinkPreview
import com.example.starbucknotetaker.Summarizer
import com.example.starbucknotetaker.extractUrls
import com.example.starbucknotetaker.normalizeUrl
import com.example.starbucknotetaker.ui.LinkPreviewCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@Composable
fun EditNoteScreen(
    note: Note,
    onSave: (String?, String, List<String>, List<NoteFile>, List<NoteLinkPreview>) -> Unit,
    onCancel: () -> Unit,
    onDisablePinCheck: () -> Unit,
    onEnablePinCheck: () -> Unit,
    summarizerState: Summarizer.SummarizerState
) {
    var title by remember { mutableStateOf(note.title) }
    val blocks = remember {
        mutableStateListOf<EditBlock>().apply {
            val lines = note.content.lines()
            var lastTextId: Long? = null
            lines.forEach { line ->
                val trimmed = line.trim()
                val imgPlaceholder = Regex("\\[\\[image:(\\d+)]]").matchEntire(trimmed)
                val filePlaceholder = Regex("\\[\\[file:(\\d+)]]").matchEntire(trimmed)
                val linkPlaceholder = Regex("\\[\\[link:(\\d+)]]").matchEntire(trimmed)
                when {
                    imgPlaceholder != null -> {
                        val idx = imgPlaceholder.groupValues[1].toInt()
                        note.images.getOrNull(idx)?.let { data ->
                            add(EditBlock.Image(data))
                            val textBlock = EditBlock.Text("")
                            add(textBlock)
                            lastTextId = textBlock.id
                        }
                    }
                    filePlaceholder != null -> {
                        val idx = filePlaceholder.groupValues[1].toInt()
                        note.files.getOrNull(idx)?.let { file ->
                            add(EditBlock.File(file))
                            val textBlock = EditBlock.Text("")
                            add(textBlock)
                            lastTextId = textBlock.id
                        }
                    }
                    linkPlaceholder != null -> {
                        val idx = linkPlaceholder.groupValues[1].toInt()
                        note.linkPreviews.getOrNull(idx)?.let { preview ->
                            val sourceId = lastTextId ?: run {
                                val textBlock = EditBlock.Text("")
                                add(textBlock)
                                lastTextId = textBlock.id
                                textBlock.id
                            }
                            add(
                                EditBlock.LinkPreview(
                                    sourceId = sourceId,
                                    preview = preview,
                                    isLoading = false,
                                    errorMessage = null,
                                    hasAttempted = true,
                                )
                            )
                            val textBlock = EditBlock.Text("")
                            add(textBlock)
                            lastTextId = textBlock.id
                        }
                    }
                    else -> {
                        val last = lastOrNull()
                        if (last is EditBlock.Text) {
                            val lastIndex = size - 1
                            val newText = if (last.text.isEmpty()) line else last.text + "\n" + line
                            val updated = last.copy(text = newText)
                            this[lastIndex] = updated
                            lastTextId = updated.id
                        } else {
                            val textBlock = EditBlock.Text(line)
                            add(textBlock)
                            lastTextId = textBlock.id
                        }
                    }
                }
            }
            if (isEmpty()) {
                add(EditBlock.Text(""))
            } else if (lastOrNull() !is EditBlock.Text) {
                val textBlock = EditBlock.Text("")
                add(textBlock)
            }
        }
    }
    val dismissedPreviewUrls = remember { mutableStateMapOf<Long, MutableSet<String>>() }
    val linkPreviewFetcher = remember { LinkPreviewFetcher() }
    val context = LocalContext.current
    val scaffoldState = rememberScaffoldState()
    val scope = rememberCoroutineScope()
    val hideKeyboard = rememberKeyboardHider()
    val focusManager = LocalFocusManager.current

    fun syncLinkPreviews(index: Int, textBlock: EditBlock.Text) {
        val normalizedUrls = extractUrls(textBlock.text).map { normalizeUrl(it) }.distinct()
        val existingBlocks = mutableMapOf<String, EditBlock.LinkPreview>()
        var cursor = index + 1
        while (cursor < blocks.size) {
            val block = blocks[cursor]
            if (block is EditBlock.LinkPreview && block.sourceId == textBlock.id) {
                existingBlocks[block.preview.url] = block
                blocks.removeAt(cursor)
            } else {
                break
            }
        }
        if (normalizedUrls.isEmpty()) {
            dismissedPreviewUrls.remove(textBlock.id)
            return
        }
        val dismissed = dismissedPreviewUrls.getOrPut(textBlock.id) { mutableSetOf() }
        dismissed.retainAll(normalizedUrls.toSet())
        val filteredUrls = normalizedUrls.filterNot { dismissed.contains(it) }
        var insertionIndex = index + 1
        filteredUrls.forEach { url ->
            val block = existingBlocks[url] ?: EditBlock.LinkPreview(
                sourceId = textBlock.id,
                preview = NoteLinkPreview(url = url)
            )
            blocks.add(insertionIndex, block)
            insertionIndex++
        }
    }

    val imagePickerContract = remember {
        OpenDocumentWithInitialUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    }

    LaunchedEffect(note.id) {
        var idx = 0
        while (idx < blocks.size) {
            val block = blocks.getOrNull(idx)
            if (block is EditBlock.Text) {
                syncLinkPreviews(idx, block)
            }
            idx++
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(imagePickerContract) { uri: Uri? ->
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

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        onEnablePinCheck()
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        val bytes = input.readBytes()
                        val name = context.contentResolver.query(it, null, null, null, null)?.use { c ->
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else "file"
                        } ?: "file"
                        val mime = context.contentResolver.getType(it) ?: "application/octet-stream"
                        NoteFile(name, mime, Base64.encodeToString(bytes, Base64.DEFAULT))
                    }
                }
                file?.let { f ->
                    val last = blocks.lastOrNull()
                    if (last is EditBlock.Text && last.text.isBlank()) {
                        blocks[blocks.size - 1] = EditBlock.File(f)
                        blocks.add(EditBlock.Text(""))
                    } else {
                        blocks.add(EditBlock.File(f))
                        blocks.add(EditBlock.Text(""))
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onEnablePinCheck()
        }
    }

    Scaffold(
        scaffoldState = scaffoldState,
        topBar = {
            TopAppBar(
                title = { Text("Edit Note") },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            hideKeyboard()
                            focusManager.clearFocus(force = true)
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
                        val files = mutableListOf<NoteFile>()
                        val linkPreviews = mutableListOf<NoteLinkPreview>()
                        val content = buildString {
                            var linkIndex = 0
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
                                    is EditBlock.File -> {
                                        append("[[file:")
                                        append(files.size)
                                        append("]]\n")
                                        files.add(block.file)
                                    }
                                    is EditBlock.LinkPreview -> {
                                        append("[[link:")
                                        append(linkIndex)
                                        append("]]\n")
                                        linkPreviews.add(block.preview)
                                        linkIndex++
                                    }
                                }
                            }
                        }.trim()
                        scope.launch {
                            hideKeyboard()
                            focusManager.clearFocus(force = true)
                            onSave(title, content, images, files, linkPreviews)
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
                SummarizerStatusBanner(state = summarizerState)
            }
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
            itemsIndexed(blocks, key = { _, block -> block.id }) { index, block ->
                when (block) {
                    is EditBlock.Text -> {
                        OutlinedTextField(
                            value = block.text,
                            onValueChange = { newText ->
                                val updated = block.copy(text = newText)
                                blocks[index] = updated
                                syncLinkPreviews(index, updated)
                            },
                            label = if (index == 0) { { Text("Content") } } else null,
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
                                            blocks[index] = block.copy(data = encoded)
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.BottomStart)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.RotateLeft,
                                        contentDescription = "Rotate",
                                        tint = Color.White
                                    )
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
                                            blocks[prevIndex] = prev.copy(text = prev.text + "\n" + next.text)
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
                    is EditBlock.File -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(block.file.name)
                            IconButton(
                                onClick = {
                                    blocks.removeAt(index)
                                    val prevIndex = index - 1
                                    if (prevIndex >= 0 && prevIndex < blocks.size) {
                                        val prev = blocks[prevIndex]
                                        val next = blocks.getOrNull(prevIndex + 1)
                                        if (prev is EditBlock.Text && next is EditBlock.Text) {
                                            blocks[prevIndex] = prev.copy(text = prev.text + "\n" + next.text)
                                            blocks.removeAt(prevIndex + 1)
                                        }
                                    }
                                },
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                    }
                    is EditBlock.LinkPreview -> {
                        val previewBlock = block
                        LaunchedEffect(previewBlock.id, previewBlock.hasAttempted, previewBlock.preview.url) {
                            if (!previewBlock.hasAttempted) {
                                when (val result = linkPreviewFetcher.fetch(previewBlock.preview.url)) {
                                    is LinkPreviewResult.Success -> {
                                        blocks[index] = previewBlock.copy(
                                            preview = result.preview,
                                            isLoading = false,
                                            errorMessage = null,
                                            hasAttempted = true,
                                        )
                                    }
                                    is LinkPreviewResult.Failure -> {
                                        blocks[index] = previewBlock.copy(
                                            isLoading = false,
                                            errorMessage = result.message ?: "Unable to load preview",
                                            hasAttempted = true,
                                        )
                                    }
                                }
                            }
                        }
                        LinkPreviewCard(
                            preview = previewBlock.preview,
                            isLoading = previewBlock.isLoading,
                            errorMessage = previewBlock.errorMessage,
                            onRemove = {
                                dismissedPreviewUrls.getOrPut(previewBlock.sourceId) { mutableSetOf() }
                                    .add(previewBlock.preview.url)
                                blocks.removeAt(index)
                                val prevIndex = index - 1
                                if (prevIndex >= 0 && prevIndex < blocks.size) {
                                    val prev = blocks[prevIndex]
                                    val next = blocks.getOrNull(prevIndex + 1)
                                    if (prev is EditBlock.Text && next is EditBlock.Text) {
                                        blocks[prevIndex] = prev.copy(text = prev.text + "\n" + next.text)
                                        blocks.removeAt(prevIndex + 1)
                                    }
                                }
                            },
                            onOpen = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(previewBlock.preview.url))
                                    )
                                }
                            }
                        )
                    }
                }
            }
            item {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = {
                            menuExpanded = true
                            onDisablePinCheck()
                        },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.InsertDriveFile, contentDescription = "Add File")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add File")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = {
                        menuExpanded = false
                        onEnablePinCheck()
                    }) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            imageLauncher.launch(arrayOf("image/*"))
                        }) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Image")
                        }
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
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

private sealed class EditBlock {
    abstract val id: Long

    data class Text(val text: String, override val id: Long = nextEditBlockId()) : EditBlock()
    data class Image(val data: String, override val id: Long = nextEditBlockId()) : EditBlock()
    data class File(val file: NoteFile, override val id: Long = nextEditBlockId()) : EditBlock()
    data class LinkPreview(
        val sourceId: Long,
        val preview: NoteLinkPreview,
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val hasAttempted: Boolean = false,
        override val id: Long = nextEditBlockId()
    ) : EditBlock()
}

private val editBlockIdGenerator = AtomicLong(0L)

private fun nextEditBlockId(): Long = editBlockIdGenerator.getAndIncrement()

