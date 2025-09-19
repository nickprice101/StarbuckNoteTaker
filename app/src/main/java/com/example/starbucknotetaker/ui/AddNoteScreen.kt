package com.example.starbucknotetaker.ui

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
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
import java.util.concurrent.atomic.AtomicLong
import com.example.starbucknotetaker.LinkPreviewFetcher
import com.example.starbucknotetaker.LinkPreviewResult
import com.example.starbucknotetaker.NoteLinkPreview
import com.example.starbucknotetaker.Summarizer
import com.example.starbucknotetaker.UrlDetection
import com.example.starbucknotetaker.extractUrls
import com.example.starbucknotetaker.ui.LinkPreviewCard

@Composable
fun AddNoteScreen(
    onSave: (String?, String, List<Pair<Uri, Int>>, List<Uri>, List<NoteLinkPreview>) -> Unit,
    onBack: () -> Unit,
    onDisablePinCheck: () -> Unit,
    onEnablePinCheck: () -> Unit,
    summarizerState: Summarizer.SummarizerState
) {
    var title by remember { mutableStateOf("") }
    val blocks = remember { mutableStateListOf<NoteBlock>(NoteBlock.Text("")) }
    val dismissedPreviewUrls = remember { mutableStateMapOf<Long, MutableSet<String>>() }
    val linkPreviewFetcher = remember { LinkPreviewFetcher() }
    val context = LocalContext.current
    val hideKeyboard = rememberKeyboardHider()
    val focusManager = LocalFocusManager.current

    fun syncLinkPreviews(
        index: Int,
        textBlock: NoteBlock.Text,
        finalizePending: Boolean = false,
    ) {
        val detections = extractUrls(textBlock.text, finalizePending)
        val existingBlocks = mutableMapOf<String, NoteBlock.LinkPreview>()
        var cursor = index + 1
        while (cursor < blocks.size) {
            val block = blocks[cursor]
            if (block is NoteBlock.LinkPreview && block.sourceId == textBlock.id) {
                existingBlocks[block.preview.url] = block
                blocks.removeAt(cursor)
            } else {
                break
            }
        }
        if (detections.isEmpty()) {
            dismissedPreviewUrls.remove(textBlock.id)
            return
        }
        val dismissed = dismissedPreviewUrls.getOrPut(textBlock.id) { mutableSetOf() }
        dismissed.retainAll(detections.map(UrlDetection::normalizedUrl).toSet())
        var insertionIndex = index + 1
        detections.forEach { detection ->
            val normalized = detection.normalizedUrl
            if (!dismissed.contains(normalized)) {
                val existing = existingBlocks[normalized]
                val block = if (existing != null) {
                    if (detection.isComplete) {
                        existing.copy(
                            preview = existing.preview.copy(url = normalized),
                            awaitingCompletion = false,
                            isLoading = if (existing.hasAttempted) false else true,
                            errorMessage = if (existing.hasAttempted) existing.errorMessage else null,
                        )
                    } else {
                        existing.copy(
                            preview = NoteLinkPreview(url = normalized),
                            awaitingCompletion = true,
                            isLoading = false,
                            errorMessage = null,
                            hasAttempted = false,
                        )
                    }
                } else {
                    if (detection.isComplete) {
                        NoteBlock.LinkPreview(
                            sourceId = textBlock.id,
                            preview = NoteLinkPreview(url = normalized),
                            isLoading = true,
                            errorMessage = null,
                            awaitingCompletion = false,
                        )
                    } else {
                        NoteBlock.LinkPreview(
                            sourceId = textBlock.id,
                            preview = NoteLinkPreview(url = normalized),
                            isLoading = false,
                            errorMessage = null,
                            hasAttempted = false,
                            awaitingCompletion = true,
                        )
                    }
                }
                blocks.add(insertionIndex, block)
                insertionIndex++
            }
        }
    }

    val imagePickerContract = remember {
        OpenDocumentWithInitialUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    }
    val imageLauncher = rememberLauncherForActivityResult(imagePickerContract) { uri: Uri? ->
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
                        var idx = 0
                        while (idx < blocks.size) {
                            val block = blocks.getOrNull(idx)
                            if (block is NoteBlock.Text) {
                                syncLinkPreviews(idx, block, finalizePending = true)
                            }
                            idx++
                        }
                        val imageList = mutableListOf<Pair<Uri, Int>>()
                        val fileList = mutableListOf<Uri>()
                        val linkPreviewList = mutableListOf<NoteLinkPreview>()
                        val content = buildString {
                            var imageIndex = 0
                            var fileIndex = 0
                            var linkIndex = 0
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
                                    is NoteBlock.LinkPreview -> {
                                        append("[[link:")
                                        append(linkIndex)
                                        append("]]\n")
                                        linkPreviewList.add(block.preview)
                                        linkIndex++
                                    }
                                }
                            }
                        }.trim()
                        hideKeyboard()
                        focusManager.clearFocus(force = true)
                        onSave(title, content, imageList, fileList, linkPreviewList)
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
                    is NoteBlock.Text -> {
                        OutlinedTextField(
                            value = block.text,
                            onValueChange = { newText ->
                                val updated = block.copy(text = newText)
                                blocks[index] = updated
                                syncLinkPreviews(index, updated)
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
                    is NoteBlock.LinkPreview -> {
                        val previewBlock = block
                        LaunchedEffect(
                            previewBlock.id,
                            previewBlock.hasAttempted,
                            previewBlock.preview.url,
                            previewBlock.awaitingCompletion,
                        ) {
                            if (!previewBlock.hasAttempted && !previewBlock.awaitingCompletion) {
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
                            awaitingCompletion = previewBlock.awaitingCompletion,
                            isLoading = previewBlock.isLoading,
                            errorMessage = previewBlock.errorMessage,
                            onRemove = {
                                dismissedPreviewUrls.getOrPut(previewBlock.sourceId) { mutableSetOf() }
                                    .add(previewBlock.preview.url)
                                blocks.removeAt(index)
                            },
                            onOpen = if (previewBlock.awaitingCompletion) {
                                null
                            } else {
                                {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(previewBlock.preview.url))
                                        )
                                    }
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
    abstract val id: Long

    data class Text(val text: String, override val id: Long = nextNoteBlockId()) : NoteBlock()
    data class Image(val uri: Uri, val rotation: Int, override val id: Long = nextNoteBlockId()) : NoteBlock()
    data class File(val uri: Uri, override val id: Long = nextNoteBlockId()) : NoteBlock()
    data class LinkPreview(
        val sourceId: Long,
        val preview: NoteLinkPreview,
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val hasAttempted: Boolean = false,
        val awaitingCompletion: Boolean = false,
        override val id: Long = nextNoteBlockId()
    ) : NoteBlock()
}

private val noteBlockIdGenerator = AtomicLong(0L)

private fun nextNoteBlockId(): Long = noteBlockIdGenerator.getAndIncrement()

