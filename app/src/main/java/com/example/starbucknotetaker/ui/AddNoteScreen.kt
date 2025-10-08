package com.example.starbucknotetaker.ui

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.util.Base64
import coil.compose.rememberAsyncImagePainter
import com.example.starbucknotetaker.LinkPreviewFetcher
import com.example.starbucknotetaker.LinkPreviewResult
import com.example.starbucknotetaker.NoteAlarmScheduler
import com.example.starbucknotetaker.NoteEvent
import com.example.starbucknotetaker.REMINDER_MINUTE_OPTIONS
import com.example.starbucknotetaker.NoteLinkPreview
import com.example.starbucknotetaker.normalizeUrl
import com.example.starbucknotetaker.Summarizer
import com.example.starbucknotetaker.UrlDetection
import com.example.starbucknotetaker.extractUrls
import com.example.starbucknotetaker.PendingShare
import com.example.starbucknotetaker.R
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import com.example.starbucknotetaker.NewNoteImage
import com.example.starbucknotetaker.richtext.RichTextDocument
import com.example.starbucknotetaker.richtext.RichTextDocumentBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AddNoteScreen(
    onSave: (String?, String, RichTextDocument, List<NewNoteImage>, List<Uri>, List<NoteLinkPreview>, NoteEvent?) -> Unit,
    onBack: () -> Unit,
    onDisablePinCheck: () -> Unit,
    onEnablePinCheck: () -> Unit,
    summarizerState: Summarizer.SummarizerState,
    entryMode: NoteEntryMode = NoteEntryMode.Note,
    initialEvent: NoteEvent? = null,
    prefill: PendingShare? = null,
) {
    var title by remember(prefill) { mutableStateOf(prefill?.title.orEmpty()) }
    val blocks = remember(prefill) {
        val initialBlocks = mutableListOf<NoteBlock>()
        prefill?.text?.takeIf { it.isNotBlank() }?.let { text ->
            initialBlocks.add(NoteBlock.Text(RichTextValue.fromPlainText(text)))
        }
        prefill?.images.orEmpty().forEach { uri ->
            initialBlocks.add(NoteBlock.Image(uri = uri, rotation = 0))
        }
        prefill?.files.orEmpty().forEach { uri ->
            initialBlocks.add(NoteBlock.File(uri))
        }
        if (initialBlocks.firstOrNull() !is NoteBlock.Text) {
            initialBlocks.add(0, NoteBlock.Text(RichTextValue.fromPlainText("")))
        }
        val needsTrailingText = initialBlocks.isEmpty() ||
            (initialBlocks.last() as? NoteBlock.Text)?.value?.text?.isNotBlank() != false
        if (needsTrailingText) {
            initialBlocks.add(NoteBlock.Text(RichTextValue.fromPlainText("")))
        }
        mutableStateListOf<NoteBlock>().apply { addAll(initialBlocks) }
    }
    val dismissedPreviewUrls = remember(prefill) { mutableStateMapOf<Long, MutableSet<String>>() }
    val context = LocalContext.current
    val linkPreviewFetcher = remember(context) { LinkPreviewFetcher(context.applicationContext) }
    val hideKeyboard = rememberKeyboardHider()
    val focusManager = LocalFocusManager.current
    var isDirty by remember(prefill, initialEvent) { mutableStateOf(false) }
    var showDiscardDialog by remember(prefill, initialEvent) { mutableStateOf(false) }

    fun markDirty() {
        if (!isDirty) {
            isDirty = true
        }
    }

    BackHandler {
        hideKeyboard()
        focusManager.clearFocus(force = true)
        if (showDiscardDialog) {
            showDiscardDialog = false
        } else if (isDirty) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

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
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var alarmEnabled by remember(initialEvent) {
        mutableStateOf(initialEvent?.alarmMinutesBeforeStart != null)
    }
    var alarmMinutes by remember(initialEvent) {
        mutableStateOf(initialEvent?.alarmMinutesBeforeStart ?: REMINDER_MINUTE_OPTIONS.getOrElse(4) { 30 })
    }
    var reminderEnabled by remember(initialEvent) {
        mutableStateOf(initialEvent?.notificationMinutesBeforeStart != null)
    }
    var reminderMinutes by remember(initialEvent) {
        mutableStateOf(initialEvent?.notificationMinutesBeforeStart ?: REMINDER_MINUTE_OPTIONS.getOrElse(4) { 30 })
    }
    var awaitingAlarmEnable by remember { mutableStateOf(false) }
    var awaitingReminderEnable by remember { mutableStateOf(false) }
    var canScheduleExactAlarm by remember { mutableStateOf(NoteAlarmScheduler.canScheduleExactAlarms(context)) }
    var pendingNotificationTarget by remember { mutableStateOf<ReminderPermissionTarget?>(null) }
    val eventHasPassed = !eventEnd.isAfter(ZonedDateTime.now(zoneId))

    LaunchedEffect(eventEnd, zoneId) {
        if (!eventEnd.isAfter(ZonedDateTime.now(zoneId))) {
            awaitingAlarmEnable = false
            awaitingReminderEnable = false
            alarmEnabled = false
            reminderEnabled = false
        }
    }
    val exactAlarmPermissionLauncher =
        rememberLauncherForActivityResult(StartActivityForResult()) {
            canScheduleExactAlarm = NoteAlarmScheduler.canScheduleExactAlarms(context)
            if (awaitingAlarmEnable) {
                if (!canScheduleExactAlarm) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.reminder_exact_alarm_permission_denied),
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (!alarmEnabled) {
                    markDirty()
                }
                alarmEnabled = true
                awaitingAlarmEnable = false
            }
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            when (pendingNotificationTarget) {
                ReminderPermissionTarget.Alarm -> {
                    if (awaitingAlarmEnable) {
                        if (granted) {
                            val launched = requestExactAlarmPermission(
                                context = context,
                                updateCanSchedule = { canScheduleExactAlarm = it },
                                onNeedsPermission = { intent -> exactAlarmPermissionLauncher.launch(intent) },
                            )
                            if (!launched) {
                                if (!alarmEnabled) {
                                    markDirty()
                                }
                                alarmEnabled = true
                                awaitingAlarmEnable = false
                            }
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.alarm_notification_permission_required),
                                Toast.LENGTH_LONG
                            ).show()
                            if (alarmEnabled) {
                                markDirty()
                            }
                            alarmEnabled = false
                            awaitingAlarmEnable = false
                        }
                    }
                }
                ReminderPermissionTarget.Reminder -> {
                    if (awaitingReminderEnable) {
                        if (granted) {
                            if (!reminderEnabled) {
                                markDirty()
                            }
                            reminderEnabled = true
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.reminder_notification_permission_required),
                                Toast.LENGTH_LONG
                            ).show()
                            if (reminderEnabled) {
                                markDirty()
                            }
                            reminderEnabled = false
                        }
                        awaitingReminderEnable = false
                    }
                }
                null -> Unit
            }
            pendingNotificationTarget = null
        }

    fun syncLinkPreviews(
        index: Int,
        textBlock: NoteBlock.Text,
        finalizePending: Boolean = false,
    ) {
        val detections = extractUrls(textBlock.value.text, finalizePending)
        val existingBlocks = mutableMapOf<String, NoteBlock.LinkPreview>()
        var cursor = index + 1
        while (cursor < blocks.size) {
            val block = blocks[cursor]
            if (block is NoteBlock.LinkPreview && block.sourceId == textBlock.id) {
                existingBlocks[normalizeUrl(block.preview.url)] = block
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
                        val hasFetched = existing.lastFetchedUrl == normalized
                        existing.copy(
                            preview = if (hasFetched) existing.preview else NoteLinkPreview(url = normalized),
                            awaitingCompletion = false,
                            isLoading = if (hasFetched) false else true,
                            errorMessage = if (hasFetched) existing.errorMessage else null,
                            hasAttempted = if (hasFetched) existing.hasAttempted else false,
                            lastFetchedUrl = existing.lastFetchedUrl.takeIf { it == normalized },
                        )
                    } else {
                        val hasFetched = existing.lastFetchedUrl == normalized
                        existing.copy(
                            preview = if (hasFetched) existing.preview else NoteLinkPreview(url = normalized),
                            awaitingCompletion = true,
                            isLoading = false,
                            errorMessage = if (hasFetched) existing.errorMessage else null,
                            hasAttempted = if (hasFetched) existing.hasAttempted else false,
                            lastFetchedUrl = existing.lastFetchedUrl.takeIf { it == normalized },
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
                            lastFetchedUrl = null,
                        )
                    } else {
                        NoteBlock.LinkPreview(
                            sourceId = textBlock.id,
                            preview = NoteLinkPreview(url = normalized),
                            isLoading = false,
                            errorMessage = null,
                            hasAttempted = false,
                            awaitingCompletion = true,
                            lastFetchedUrl = null,
                        )
                    }
                }
                blocks.add(insertionIndex, block)
                insertionIndex++
            }
        }
    }

    fun ensureTrailingTextBlock() {
        val last = blocks.lastOrNull()
        if (last !is NoteBlock.Text || last.value.text.isNotBlank()) {
            blocks.add(NoteBlock.Text(RichTextValue.fromPlainText("")))
        }
    }

    LaunchedEffect(prefill) {
        if (!prefill?.text.isNullOrBlank()) {
            val index = blocks.indexOfFirst { it is NoteBlock.Text && it.value.text.isNotBlank() }
            val block = blocks.getOrNull(index) as? NoteBlock.Text ?: return@LaunchedEffect
            syncLinkPreviews(index, block, finalizePending = true)
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
            if (last is NoteBlock.Text && last.value.text.isBlank()) {
                blocks[blocks.size - 1] = NoteBlock.Image(uri = it, rotation = 0)
                blocks.add(NoteBlock.Text(RichTextValue.fromPlainText("")))
            } else {
                blocks.add(NoteBlock.Image(uri = it, rotation = 0))
                blocks.add(NoteBlock.Text(RichTextValue.fromPlainText("")))
            }
            markDirty()
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
            if (last is NoteBlock.Text && last.value.text.isBlank()) {
                blocks[blocks.size - 1] = NoteBlock.File(it)
                blocks.add(NoteBlock.Text(RichTextValue.fromPlainText("")))
            } else {
                blocks.add(NoteBlock.File(it))
                blocks.add(NoteBlock.Text(RichTextValue.fromPlainText("")))
            }
            markDirty()
        }
        onEnablePinCheck()
    }

    fun appendTranscribedText(transcribed: String) {
        val sanitized = transcribed.trim()
        if (sanitized.isEmpty()) return
        markDirty()
        val lastIndex = blocks.lastIndex
        val last = blocks.getOrNull(lastIndex)
        if (last is NoteBlock.Text && last.value.text.isBlank()) {
            val updated = last.copy(value = RichTextValue.fromPlainText(sanitized))
            blocks[lastIndex] = updated
            syncLinkPreviews(lastIndex, updated, finalizePending = true)
            blocks.add(NoteBlock.Text(RichTextValue.fromPlainText("")))
        } else {
            val newBlock = NoteBlock.Text(RichTextValue.fromPlainText(sanitized))
            blocks.add(newBlock)
            val index = blocks.lastIndex
            syncLinkPreviews(index, newBlock, finalizePending = true)
            blocks.add(NoteBlock.Text(RichTextValue.fromPlainText("")))
        }
    }

    var showTranscriptionDialog by remember { mutableStateOf(false) }
    var showSketchPad by remember { mutableStateOf(false) }
    var showAudioPermissionSettingsDialog by remember { mutableStateOf(false) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showTranscriptionDialog = true
        } else {
            val activity = context.findActivity()
            val shouldShowRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it,
                    Manifest.permission.RECORD_AUDIO
                )
            } ?: true
            if (!shouldShowRationale) {
                showAudioPermissionSettingsDialog = true
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.microphone_permission_toast),
                    Toast.LENGTH_LONG
                ).show()
            }
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
                        if (isDirty) {
                            showDiscardDialog = true
                        } else {
                            onBack()
                        }
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
                        val imageList = mutableListOf<NewNoteImage>()
                        val fileList = mutableListOf<Uri>()
                        val linkPreviewList = mutableListOf<NoteLinkPreview>()
                        val contentBuilder = StringBuilder()
                        val styledBuilder = RichTextDocumentBuilder()
                        var linkIndex = 0
                        blocks.forEach { block ->
                            when (block) {
                                is NoteBlock.Text -> {
                                    styledBuilder.append(block.value.toDocument())
                                    styledBuilder.appendPlain("\n")
                                    contentBuilder.append(block.value.text)
                                    contentBuilder.append('\n')
                                }
                                is NoteBlock.Image -> {
                                    styledBuilder.appendPlain("[[image:${imageList.size}]]\n")
                                    contentBuilder.append("[[image:")
                                    contentBuilder.append(imageList.size)
                                    contentBuilder.append("]]\n")
                                    imageList.add(
                                        NewNoteImage(
                                            uri = block.uri,
                                            rotation = block.rotation,
                                            data = block.data?.takeIf { it.isNotBlank() },
                                        )
                                    )
                                }
                                is NoteBlock.File -> {
                                    styledBuilder.appendPlain("[[file:${fileList.size}]]\n")
                                    contentBuilder.append("[[file:")
                                    contentBuilder.append(fileList.size)
                                    contentBuilder.append("]]\n")
                                    fileList.add(block.uri)
                                }
                                is NoteBlock.LinkPreview -> {
                                    styledBuilder.appendPlain("[[link:$linkIndex]]\n")
                                    contentBuilder.append("[[link:")
                                    contentBuilder.append(linkIndex)
                                    contentBuilder.append("]]\n")
                                    linkPreviewList.add(block.preview)
                                    linkIndex++
                                }
                            }
                        }
                        val content = contentBuilder.toString().trim()
                        val styledContent = styledBuilder.build().trimmed()
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
                                alarmMinutesBeforeStart = if (alarmEnabled) alarmMinutes else null,
                                notificationMinutesBeforeStart = if (reminderEnabled) reminderMinutes else null,
                            )
                        } else {
                            null
                        }
                        onSave(title, content, styledContent, imageList, fileList, linkPreviewList, event)
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        val imeSpacer = (
            WindowInsets.ime.asPaddingValues().calculateBottomPadding() -
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            ).coerceAtLeast(0.dp)
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .imeNestedScroll(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + imeSpacer,
            )
        ) {
            item {
                SummarizerStatusBanner(state = summarizerState)
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { newTitle ->
                            if (title != newTitle) {
                                markDirty()
                                title = newTitle
                            }
                        },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (entryMode == NoteEntryMode.Event) {
                item {
                    Column(modifier = Modifier.padding(bottom = 16.dp)) {
                        LocationAutocompleteField(
                            value = eventLocation,
                            onValueChange = { newLocation ->
                                if (eventLocation != newLocation) {
                                    eventLocation = newLocation
                                    markDirty()
                                }
                            },
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
                                if (eventAllDay != checked) {
                                    eventAllDay = checked
                                    markDirty()
                                }
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
                                    if (eventStart != newDate) {
                                        eventStart = newDate
                                        markDirty()
                                    }
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
                                    if (eventStart != newTime) {
                                        eventStart = newTime
                                        markDirty()
                                    }
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
                                    val updated = if (eventAllDay) {
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
                                    if (eventEnd != updated) {
                                        eventEnd = updated
                                        markDirty()
                                    } else {
                                        eventEnd = updated
                                    }
                                },
                                onTimeChange = { newTime ->
                                    val updated = if (eventAllDay) {
                                        eventStart.plusDays(1)
                                    } else {
                                        if (newTime.isBefore(eventStart)) {
                                            eventStart.plusHours(1)
                                        } else {
                                            newTime
                                        }
                                    }
                                    if (eventEnd != updated) {
                                        eventEnd = updated
                                        markDirty()
                                    } else {
                                        eventEnd = updated
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
                                if (zoneId != newZone) {
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
                                    markDirty()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!eventHasPassed) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = stringResource(R.string.event_alarm_toggle_label))
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = alarmEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            awaitingAlarmEnable = true
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                val granted = ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.POST_NOTIFICATIONS
                                                ) == PackageManager.PERMISSION_GRANTED
                                                if (!granted) {
                                                    pendingNotificationTarget = ReminderPermissionTarget.Alarm
                                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                    return@Switch
                                                }
                                            }
                                            val launched = requestExactAlarmPermission(
                                                context = context,
                                                updateCanSchedule = { canScheduleExactAlarm = it },
                                                onNeedsPermission = { intent -> exactAlarmPermissionLauncher.launch(intent) },
                                            )
                                            if (!launched) {
                                                if (!alarmEnabled) {
                                                    markDirty()
                                                }
                                                alarmEnabled = true
                                                awaitingAlarmEnable = false
                                            }
                                        } else {
                                            awaitingAlarmEnable = false
                                            if (alarmEnabled) {
                                                markDirty()
                                            }
                                            alarmEnabled = false
                                        }
                                    }
                                )
                            }
                            if (alarmEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarm) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.reminder_exact_alarm_permission_needed),
                                    style = MaterialTheme.typography.body2,
                                )
                                TextButton(onClick = {
                                    requestExactAlarmPermission(
                                        context = context,
                                        updateCanSchedule = { canScheduleExactAlarm = it },
                                        onNeedsPermission = { intent -> exactAlarmPermissionLauncher.launch(intent) },
                                    )
                                }) {
                                    Text(text = stringResource(R.string.reminder_exact_alarm_permission_action))
                                }
                            }
                            if (alarmEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                ReminderOffsetDropdown(
                                    selectedMinutes = alarmMinutes,
                                    onMinutesSelected = { minutes ->
                                        if (alarmMinutes != minutes) {
                                            alarmMinutes = minutes
                                            markDirty()
                                        }
                                    },
                                    fieldLabel = stringResource(R.string.event_alarm_lead_time_label),
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = stringResource(R.string.event_reminder_toggle_label))
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = reminderEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            awaitingReminderEnable = true
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                val granted = ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.POST_NOTIFICATIONS
                                                ) == PackageManager.PERMISSION_GRANTED
                                                if (!granted) {
                                                    pendingNotificationTarget = ReminderPermissionTarget.Reminder
                                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                    return@Switch
                                                }
                                            }
                                            if (!reminderEnabled) {
                                                markDirty()
                                            }
                                            reminderEnabled = true
                                            awaitingReminderEnable = false
                                        } else {
                                            awaitingReminderEnable = false
                                            if (reminderEnabled) {
                                                markDirty()
                                            }
                                            reminderEnabled = false
                                        }
                                    }
                                )
                            }
                            if (reminderEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                ReminderOffsetDropdown(
                                    selectedMinutes = reminderMinutes,
                                    onMinutesSelected = { minutes ->
                                        if (reminderMinutes != minutes) {
                                            reminderMinutes = minutes
                                            markDirty()
                                        }
                                    },
                                    fieldLabel = stringResource(R.string.event_reminder_lead_time_label),
                                )
                            }
                        }
                    }
                }
            }
            itemsIndexed(blocks, key = { _, block -> block.id }) { index, block ->
                val blockIndex = index
                when (block) {
                    is NoteBlock.Text -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            RichTextEditor(
                                value = block.value,
                                onValueChange = { newValue ->
                                    val updated = block.copy(value = newValue)
                                    blocks[blockIndex] = updated
                                    markDirty()
                                    syncLinkPreviews(blockIndex, updated)
                                },
                                label = if (blockIndex == 0 && entryMode == NoteEntryMode.Note) {
                                    { Text("Content") }
                                } else null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    is NoteBlock.Image -> {
                        val imageBitmap = remember(block.data) {
                            block.data?.takeIf { it.isNotBlank() }?.let { data ->
                                runCatching {
                                    val bytes = Base64.decode(data, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                }.getOrNull()
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            when {
                                imageBitmap != null -> {
                                    Image(
                                        bitmap = imageBitmap,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer(rotationZ = block.rotation.toFloat())
                                    )
                                }
                                block.uri != null -> {
                                    Image(
                                        painter = rememberAsyncImagePainter(block.uri),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer(rotationZ = block.rotation.toFloat())
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    blocks[index] = block.copy(rotation = (block.rotation + 270) % 360)
                                    markDirty()
                                },
                                modifier = Modifier.align(Alignment.BottomStart)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.RotateLeft,
                                    contentDescription = "Rotate",
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (block.data != null) {
                                        blocks[index] = block.copy(data = null)
                                    }
                                    blocks.removeAt(index)
                                    markDirty()
                                    ensureTrailingTextBlock()
                                },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.remove_image_content_description),
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
                            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = name,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    blocks.removeAt(index)
                                    markDirty()
                                    ensureTrailingTextBlock()
                                }
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.remove_file_content_description),
                                )
                            }
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
                                            lastFetchedUrl = result.preview.url,
                                        )
                                    }
                                    is LinkPreviewResult.Failure -> {
                                        blocks[index] = previewBlock.copy(
                                            isLoading = false,
                                            errorMessage = result.message ?: "Unable to load preview",
                                            hasAttempted = true,
                                            lastFetchedUrl = previewBlock.preview.url,
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
                                markDirty()
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
                        icon = Icons.AutoMirrored.Filled.InsertDriveFile,
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
                        icon = Icons.Default.Edit,
                        label = "Sketch",
                    ) {
                        onDisablePinCheck()
                        showSketchPad = true
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

    if (showSketchPad) {
        SketchPadDialog(
            onDismiss = {
                showSketchPad = false
                onEnablePinCheck()
            },
            onSave = { bytes ->
                val encoded = Base64.encodeToString(bytes, Base64.DEFAULT)
                val last = blocks.lastOrNull()
                if (last is NoteBlock.Text && last.value.text.isBlank()) {
                    blocks[blocks.size - 1] = NoteBlock.Image(data = encoded)
                    blocks.add(NoteBlock.Text(RichTextValue.fromPlainText("")))
                } else {
                    blocks.add(NoteBlock.Image(data = encoded))
                    blocks.add(NoteBlock.Text(RichTextValue.fromPlainText("")))
                }
                markDirty()
                showSketchPad = false
                onEnablePinCheck()
            }
        )
    }

    if (showTranscriptionDialog) {
        AudioTranscriptionDialog(
            onDismiss = {
                showTranscriptionDialog = false
                onEnablePinCheck()
            },
            onResult = { result ->
                appendTranscribedText(result)
            },
            onRequireAudioPermission = {
                showTranscriptionDialog = false
                onEnablePinCheck()
                showAudioPermissionSettingsDialog = true
            }
        )
    }

    if (showAudioPermissionSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showAudioPermissionSettingsDialog = false },
            title = { Text(stringResource(R.string.microphone_permission_required_title)) },
            text = { Text(stringResource(R.string.microphone_permission_required_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAudioPermissionSettingsDialog = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                ) {
                    Text(stringResource(R.string.microphone_permission_settings_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAudioPermissionSettingsDialog = false }) {
                    Text(stringResource(R.string.microphone_permission_settings_dismiss))
                }
            }
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes. Do you want to discard them?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        hideKeyboard()
                        focusManager.clearFocus(force = true)
                        onBack()
                    }
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep editing")
                }
            }
        )
    }
}


private sealed class NoteBlock {
    abstract val id: Long

    data class Text(val value: RichTextValue, override val id: Long = nextNoteBlockId()) : NoteBlock()
    data class Image(
        val uri: Uri? = null,
        val rotation: Int = 0,
        val data: String? = null,
        override val id: Long = nextNoteBlockId(),
    ) : NoteBlock()
    data class File(val uri: Uri, override val id: Long = nextNoteBlockId()) : NoteBlock()
    data class LinkPreview(
        val sourceId: Long,
        val preview: NoteLinkPreview,
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val hasAttempted: Boolean = false,
        val awaitingCompletion: Boolean = false,
        val lastFetchedUrl: String? = null,
        override val id: Long = nextNoteBlockId(),
    ) : NoteBlock()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
