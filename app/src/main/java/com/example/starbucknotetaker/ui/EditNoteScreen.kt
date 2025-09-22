package com.example.starbucknotetaker.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.speech.SpeechRecognizer
import android.util.Base64
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
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.example.starbucknotetaker.Note
import com.example.starbucknotetaker.NoteFile
import com.example.starbucknotetaker.NoteImage
import com.example.starbucknotetaker.NoteEvent
import com.example.starbucknotetaker.LinkPreviewFetcher
import com.example.starbucknotetaker.LinkPreviewResult
import com.example.starbucknotetaker.NoteLinkPreview
import com.example.starbucknotetaker.Summarizer
import com.example.starbucknotetaker.UrlDetection
import com.example.starbucknotetaker.extractUrls
import com.example.starbucknotetaker.ui.LinkPreviewCard
import com.example.starbucknotetaker.REMINDER_MINUTE_OPTIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun EditNoteScreen(
    note: Note,
    onSave: (String?, String, List<NoteImage>, List<NoteFile>, List<NoteLinkPreview>, NoteEvent?) -> Unit,
    onCancel: () -> Unit,
    onDisablePinCheck: () -> Unit,
    onEnablePinCheck: () -> Unit,
    summarizerState: Summarizer.SummarizerState,
    openAttachment: suspend (String) -> ByteArray?,
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
                        note.images.getOrNull(idx)?.let { image ->
                            add(EditBlock.Image(image.attachmentId, image.data.orEmpty()))
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
    val context = LocalContext.current
    val linkPreviewFetcher = remember(context) { LinkPreviewFetcher(context.applicationContext) }
    val scaffoldState = rememberScaffoldState()
    val scope = rememberCoroutineScope()
    val hideKeyboard = rememberKeyboardHider()
    val focusManager = LocalFocusManager.current
    val isEvent = note.event != null
    val initialZone = remember(note.event) {
        note.event?.let { runCatching { ZoneId.of(it.timeZone) }.getOrNull() } ?: ZoneId.systemDefault()
    }
    var zoneId by remember(note.event) { mutableStateOf(initialZone) }
    var eventLocation by remember(note.event) { mutableStateOf(note.event?.location ?: "") }
    var eventAllDay by remember(note.event) { mutableStateOf(note.event?.allDay ?: false) }
    var eventStart by remember(note.event) {
        mutableStateOf(
            note.event?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.start), initialZone) }
                ?.truncatedTo(ChronoUnit.MINUTES)
                ?: ZonedDateTime.now(initialZone).truncatedTo(ChronoUnit.MINUTES)
        )
    }
    var eventEnd by remember(note.event) {
        mutableStateOf(
            note.event?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.end), initialZone) }
                ?.truncatedTo(ChronoUnit.MINUTES)
                ?: ZonedDateTime.now(initialZone).plusHours(1).truncatedTo(ChronoUnit.MINUTES)
        )
    }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, MMM d, yyyy") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var reminderEnabled by remember(note.event) {
        mutableStateOf(note.event?.reminderMinutesBeforeStart != null)
    }
    var reminderMinutes by remember(note.event) {
        mutableStateOf(note.event?.reminderMinutesBeforeStart ?: REMINDER_MINUTE_OPTIONS.getOrElse(4) { 30 })
    }
    var pendingReminderPermission by remember { mutableStateOf(false) }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (pendingReminderPermission) {
                if (granted) {
                    reminderEnabled = true
                } else {
                    Toast.makeText(
                        context,
                        "Notification permission is required to enable reminders",
                        Toast.LENGTH_LONG
                    ).show()
                    reminderEnabled = false
                }
                pendingReminderPermission = false
            }
        }

    fun syncLinkPreviews(
        index: Int,
        textBlock: EditBlock.Text,
        finalizePending: Boolean = false,
    ) {
        val detections = extractUrls(textBlock.text, finalizePending)
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
                        EditBlock.LinkPreview(
                            sourceId = textBlock.id,
                            preview = NoteLinkPreview(url = normalized),
                            isLoading = true,
                            errorMessage = null,
                            awaitingCompletion = false,
                        )
                    } else {
                        EditBlock.LinkPreview(
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

    LaunchedEffect(note.id) {
        var idx = 0
        while (idx < blocks.size) {
            val block = blocks.getOrNull(idx)
            if (block is EditBlock.Text) {
                syncLinkPreviews(idx, block, finalizePending = true)
            }
            idx++
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        onEnablePinCheck()
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
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
                        blocks[blocks.size - 1] = EditBlock.Image(null, data)
                        blocks.add(EditBlock.Text(""))
                    } else {
                        blocks.add(EditBlock.Image(null, data))
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
                        NoteFile(name, mime, attachmentId = null, data = Base64.encodeToString(bytes, Base64.DEFAULT))
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

    fun appendTranscribedText(transcribed: String) {
        val sanitized = transcribed.trim()
        if (sanitized.isEmpty()) return
        val lastIndex = blocks.lastIndex
        val last = blocks.getOrNull(lastIndex)
        if (last is EditBlock.Text && last.text.isBlank()) {
            val updated = last.copy(text = sanitized)
            blocks[lastIndex] = updated
            syncLinkPreviews(lastIndex, updated, finalizePending = true)
            blocks.add(EditBlock.Text(""))
        } else {
            val newBlock = EditBlock.Text(sanitized)
            blocks.add(newBlock)
            val index = blocks.lastIndex
            syncLinkPreviews(index, newBlock, finalizePending = true)
            blocks.add(EditBlock.Text(""))
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
                        hideKeyboard()
                        focusManager.clearFocus(force = true)
                        onCancel()
                        scope.launch {
                            scaffoldState.snackbarHostState.showSnackbar(
                                "Changes discarded",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Discard")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        var idx = 0
                        while (idx < blocks.size) {
                            val block = blocks.getOrNull(idx)
                            if (block is EditBlock.Text) {
                                syncLinkPreviews(idx, block, finalizePending = true)
                            }
                            idx++
                        }
                        val images = mutableListOf<NoteImage>()
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
                                        images.add(
                                            NoteImage(
                                                attachmentId = block.attachmentId,
                                                data = block.data.takeIf { it.isNotBlank() }
                                            )
                                        )
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
                            val eventForSave = if (isEvent) {
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
                                    reminderMinutesBeforeStart = if (reminderEnabled) reminderMinutes else null,
                                )
                            } else {
                                note.event
                            }
                            onSave(title, content, images, files, linkPreviews, eventForSave)
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
            if (isEvent) {
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
                        EventDateTimePickerEditable(
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
                        EventDateTimePickerEditable(
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
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Reminder")
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = reminderEnabled,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            val granted = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (!granted) {
                                                pendingReminderPermission = true
                                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                return@Switch
                                            }
                                        }
                                        reminderEnabled = true
                                    } else {
                                        reminderEnabled = false
                                    }
                                }
                            )
                        }
                        if (reminderEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            ReminderOffsetDropdown(
                                selectedMinutes = reminderMinutes,
                                onMinutesSelected = { reminderMinutes = it },
                            )
                        }
                    }
                }
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
                            label = if (index == 0 && !isEvent) {
                                { Text("Content") }
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }
                    is EditBlock.Image -> {
                        var bitmap by remember(block.id, block.data) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(block.id, block.attachmentId, block.data) {
                            if (block.data.isBlank() && block.attachmentId != null) {
                                val bytes = withContext(Dispatchers.IO) { openAttachment(block.attachmentId) }
                                if (bytes != null) {
                                    val encoded = Base64.encodeToString(bytes, Base64.DEFAULT)
                                    blocks[index] = block.copy(data = encoded)
                                }
                            }
                        }
                        LaunchedEffect(block.id, block.data) {
                            bitmap = if (block.data.isNotBlank()) {
                                withContext(Dispatchers.IO) {
                                    val bytes = Base64.decode(block.data, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }
                            } else {
                                null
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

@Composable
private fun EventDateTimePickerEditable(
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
        Text(label, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
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
                            true,
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

private sealed class EditBlock {
    abstract val id: Long

    data class Text(val text: String, override val id: Long = nextEditBlockId()) : EditBlock()
    data class Image(
        val attachmentId: String?,
        val data: String,
        override val id: Long = nextEditBlockId()
    ) : EditBlock()
    data class File(val file: NoteFile, override val id: Long = nextEditBlockId()) : EditBlock()
    data class LinkPreview(
        val sourceId: Long,
        val preview: NoteLinkPreview,
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val hasAttempted: Boolean = false,
        val awaitingCompletion: Boolean = false,
        override val id: Long = nextEditBlockId()
    ) : EditBlock()
}

private val editBlockIdGenerator = AtomicLong(0L)

private fun nextEditBlockId(): Long = editBlockIdGenerator.getAndIncrement()

