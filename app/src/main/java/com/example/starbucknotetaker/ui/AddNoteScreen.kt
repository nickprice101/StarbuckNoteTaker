package com.example.starbucknotetaker.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.starbucknotetaker.LinkPreviewFetcher
import com.example.starbucknotetaker.LinkPreviewResult
import com.example.starbucknotetaker.NoteEvent
import com.example.starbucknotetaker.NoteLinkPreview
import com.example.starbucknotetaker.Summarizer
import com.example.starbucknotetaker.UrlDetection
import com.example.starbucknotetaker.extractUrls
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

@Composable
fun AddNoteScreen(
    onSave: (String?, String, List<Pair<Uri, Int>>, List<Uri>, List<NoteLinkPreview>, NoteEvent?) -> Unit,
    onBack: () -> Unit,
    onDisablePinCheck: () -> Unit,
    onEnablePinCheck: () -> Unit,
    summarizerState: Summarizer.SummarizerState,
    entryMode: NoteEntryMode = NoteEntryMode.Note,
    initialEvent: NoteEvent? = null,
) {
    var title by remember { mutableStateOf("") }
    val blocks = remember { mutableStateListOf<NoteBlock>(NoteBlock.Text("")) }
    val dismissedPreviewUrls = remember { mutableStateMapOf<Long, MutableSet<String>>() }
    val context = LocalContext.current
    val linkPreviewFetcher = remember(context) { LinkPreviewFetcher(context.applicationContext) }
    val hideKeyboard = rememberKeyboardHider()
    val focusManager = LocalFocusManager.current

    val initialZone = remember(initialEvent) {
        initialEvent?.let { runCatching { ZoneId.of(it.timeZone) }.getOrNull() } ?: ZoneId.systemDefault()
    }
    var zoneId by remember(initialEvent) { mutableStateOf(initialZone) }
    var eventLocation by remember(initialEvent) { mutableStateOf(initialEvent?.location ?: "") }
    var eventAllDay by remember(initialEvent) { mutableStateOf(initialEvent?.allDay ?: false) }
    var eventStart by remember(initialEvent) {
        mutableStateOf(
            initialEvent?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.start), initialZone) }
                ?.truncatedTo(ChronoUnit.MINUTES)
                ?: ZonedDateTime.now(initialZone).truncatedTo(ChronoUnit.MINUTES)
        )
    }
    var eventEnd by remember(initialEvent) {
        mutableStateOf(
            initialEvent?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.end), initialZone) }
                ?.truncatedTo(ChronoUnit.MINUTES)
                ?: ZonedDateTime.now(initialZone).plusHours(1).truncatedTo(ChronoUnit.MINUTES)
        )
    }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, MMM d, yyyy") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("h:mm a") }

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

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
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

    fun appendTranscribedText(transcribed: String) {
        val sanitized = transcribed.trim()
        if (sanitized.isEmpty()) return
        val lastIndex = blocks.lastIndex
        val last = blocks.getOrNull(lastIndex)
        if (last is NoteBlock.Text && last.text.isBlank()) {
            val updated = last.copy(text = sanitized)
            blocks[lastIndex] = updated
            syncLinkPreviews(lastIndex, updated, finalizePending = true)
            blocks.add(NoteBlock.Text(""))
        } else {
            val newBlock = NoteBlock.Text(sanitized)
            blocks.add(newBlock)
            val index = blocks.lastIndex
            syncLinkPreviews(index, newBlock, finalizePending = true)
            blocks.add(NoteBlock.Text(""))
        }
    }

    var showTranscriptionDialog by remember { mutableStateOf(false) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showTranscriptionDialog = true
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required to transcribe audio",
                Toast.LENGTH_LONG
            ).show()
            onEnablePinCheck()
        }
    }

    DisposableEffect(Unit) {
        onDispose { onEnablePinCheck() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (entryMode) {
                            NoteEntryMode.Note -> "New Note"
                            NoteEntryMode.Event -> "New Event"
                        }
                    )
                },
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
                        val event = if (entryMode == NoteEntryMode.Event) {
                            val normalizedStart = if (eventAllDay) {
                                eventStart.withHour(0).withMinute(0)
                            } else {
                                eventStart
                            }
                            val normalizedEnd = if (eventAllDay) {
                                val candidate = eventEnd.withHour(0).withMinute(0)
                                if (candidate.toLocalDate().isBefore(normalizedStart.toLocalDate())) {
                                    normalizedStart.plusDays(1)
                                } else {
                                    candidate
                                }
                            } else {
                                if (eventEnd.isBefore(normalizedStart)) {
                                    normalizedStart.plusHours(1)
                                } else {
                                    eventEnd
                                }
                            }
                            eventStart = normalizedStart
                            eventEnd = normalizedEnd
                            NoteEvent(
                                start = normalizedStart.toInstant().toEpochMilli(),
                                end = normalizedEnd.toInstant().toEpochMilli(),
                                allDay = eventAllDay,
                                timeZone = zoneId.id,
                                location = eventLocation.takeIf { it.isNotBlank() },
                            )
                        } else {
                            null
                        }
                        onSave(title, content, imageList, fileList, linkPreviewList, event)
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
            if (entryMode == NoteEntryMode.Event) {
                item {
                    Column(modifier = Modifier.padding(bottom = 16.dp)) {
                        LocationAutocompleteField(
                            value = eventLocation,
                            onValueChange = { eventLocation = it },
                            label = "Location",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("All-day")
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(checked = eventAllDay, onCheckedChange = { checked ->
                                eventAllDay = checked
                                if (checked) {
                                    eventStart = eventStart.withHour(0).withMinute(0)
                                    eventEnd = if (eventEnd.toLocalDate().isBefore(eventStart.toLocalDate())) {
                                        eventStart.plusDays(1)
                                    } else {
                                        eventEnd.withHour(0).withMinute(0)
                                    }
                                } else {
                                    if (eventEnd.isBefore(eventStart)) {
                                        eventEnd = eventStart.plusHours(1)
                                    }
                                }
                            })
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        EventDateTimePicker(
                            label = "Starts",
                            date = eventStart,
                            onDateChange = { newDate ->
                                eventStart = newDate
                                if (eventAllDay) {
                                    eventStart = eventStart.withHour(0).withMinute(0)
                                    if (eventEnd.toLocalDate().isBefore(eventStart.toLocalDate())) {
                                        eventEnd = eventStart.plusDays(1)
                                    }
                                } else if (eventEnd.isBefore(eventStart)) {
                                    eventEnd = eventStart.plusHours(1)
                                }
                            },
                            onTimeChange = { newTime ->
                                eventStart = newTime
                                if (!eventAllDay && eventEnd.isBefore(eventStart)) {
                                    eventEnd = eventStart.plusHours(1)
                                }
                            },
                            allDay = eventAllDay,
                            dateFormatter = dateFormatter,
                            timeFormatter = timeFormatter,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        EventDateTimePicker(
                            label = "Ends",
                            date = eventEnd,
                            onDateChange = { newDate ->
                                eventEnd = if (eventAllDay) {
                                    val normalized = newDate.withHour(0).withMinute(0)
                                    if (normalized.toLocalDate().isBefore(eventStart.toLocalDate())) {
                                        eventStart.plusDays(1)
                                    } else {
                                        normalized
                                    }
                                } else {
                                    if (newDate.isBefore(eventStart)) {
                                        eventStart.plusHours(1)
                                    } else {
                                        newDate
                                    }
                                }
                            },
                            onTimeChange = { newTime ->
                                if (eventAllDay) {
                                    eventEnd = eventStart.plusDays(1)
                                } else {
                                    eventEnd = if (newTime.isBefore(eventStart)) {
                                        eventStart.plusHours(1)
                                    } else {
                                        newTime
                                    }
                                }
                            },
                            allDay = eventAllDay,
                            dateFormatter = dateFormatter,
                            timeFormatter = timeFormatter,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TimeZonePicker(
                            zoneId = zoneId,
                            onZoneChange = { newZone ->
                                val convertedStart = eventStart.withZoneSameInstant(newZone)
                                    .truncatedTo(ChronoUnit.MINUTES)
                                val convertedEnd = eventEnd.withZoneSameInstant(newZone)
                                    .truncatedTo(ChronoUnit.MINUTES)
                                val adjustedStart = if (eventAllDay) {
                                    convertedStart.withHour(0).withMinute(0)
                                } else {
                                    convertedStart
                                }
                                val adjustedEnd = if (eventAllDay) {
                                    val normalized = convertedEnd.withHour(0).withMinute(0)
                                    if (normalized.toLocalDate().isBefore(adjustedStart.toLocalDate())) {
                                        adjustedStart.plusDays(1)
                                    } else {
                                        normalized
                                    }
                                } else {
                                    if (convertedEnd.isBefore(adjustedStart)) {
                                        adjustedStart.plusHours(1)
                                    } else {
                                        convertedEnd
                                    }
                                }
                                zoneId = newZone
                                eventStart = adjustedStart
                                eventEnd = adjustedEnd
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
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
                            label = if (index == 0 && entryMode == NoteEntryMode.Note) {
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
                            },
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AttachmentAction(
                        icon = Icons.Default.InsertDriveFile,
                        label = "Add File",
                    ) {
                        onDisablePinCheck()
                        fileLauncher.launch(arrayOf("*/*"))
                    }
                    AttachmentAction(
                        icon = Icons.Default.Image,
                        label = "Add Image",
                    ) {
                        onDisablePinCheck()
                        imageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    AttachmentAction(
                        icon = Icons.Default.Mic,
                        label = "Transcribe",
                    ) {
                        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                            Toast.makeText(
                                context,
                                "Speech recognition is not available on this device",
                                Toast.LENGTH_LONG
                            ).show()
                            return@AttachmentAction
                        }
                        val permissionGranted =
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        onDisablePinCheck()
                        if (permissionGranted) {
                            showTranscriptionDialog = true
                        } else {
                            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            }
        }
    }

    if (showTranscriptionDialog) {
        AudioTranscriptionDialog(
            onDismiss = {
                showTranscriptionDialog = false
                onEnablePinCheck()
            },
            onResult = { result ->
                appendTranscribedText(result)
            }
        )
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
        override val id: Long = nextNoteBlockId(),
    ) : NoteBlock()
}

@Composable
private fun AttachmentAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.caption)
    }
}

private val noteBlockIdGenerator = AtomicLong(0L)

private fun nextNoteBlockId(): Long = noteBlockIdGenerator.getAndIncrement()

enum class NoteEntryMode {
    Note,
    Event,
}

@Composable
private fun EventDateTimePicker(
    label: String,
    date: ZonedDateTime,
    onDateChange: (ZonedDateTime) -> Unit,
    onTimeChange: (ZonedDateTime) -> Unit,
    allDay: Boolean,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter,
) {
    val context = LocalContext.current
    Column {
        Text(label, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            val updated = date.withYear(year).withMonth(month + 1).withDayOfMonth(dayOfMonth)
                            onDateChange(updated)
                        },
                        date.year,
                        date.monthValue - 1,
                        date.dayOfMonth,
                    ).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(dateFormatter.format(date))
            }
            if (!allDay) {
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hourOfDay, minute ->
                                val updated = date.withHour(hourOfDay).withMinute(minute)
                                onTimeChange(updated)
                            },
                            date.hour,
                            date.minute,
                            false,
                        ).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(timeFormatter.format(date))
                }
            }
        }
    }
}
