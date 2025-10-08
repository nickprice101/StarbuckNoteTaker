package com.example.starbucknotetaker.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Patterns
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.Note
import com.example.starbucknotetaker.NoteEvent
import com.example.starbucknotetaker.formatReminderOffsetMinutes
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.time.Instant
import java.util.ArrayList
import java.util.Locale
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import com.example.starbucknotetaker.ChecklistItem
import com.example.starbucknotetaker.richtext.RichTextDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NoteDetailScreen(
    note: Note,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onLockRequest: () -> Unit,
    onUnlockRequest: () -> Unit,
    openAttachment: suspend (String) -> ByteArray?,
    onChecklistChange: (List<ChecklistItem>) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var fullImage by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()
    val eventLocationDisplay = rememberEventLocationDisplay(note.event?.location)
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(
                    note.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        if (note.isLocked) {
                            onUnlockRequest()
                        } else {
                            onLockRequest()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (note.isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = if (note.isLocked) "Unlock note" else "Lock note"
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            val preparation = prepareShareAttachments(context, note, openAttachment)
                            if (preparation.errors.isNotEmpty()) {
                                Toast.makeText(
                                    context,
                                    preparation.errors.joinToString(separator = "\n"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            val shareText = buildShareText(note, eventLocationDisplay)
                            val attachments = preparation.attachments
                            if (shareText.isBlank() && attachments.isEmpty()) {
                                Toast.makeText(context, "Nothing to share", Toast.LENGTH_SHORT).show()
                                cleanupSharedFiles(context, attachments)
                                return@launch
                            }
                            val baseIntent = buildShareIntent(context, note, shareText, attachments)
                            val chooser = Intent.createChooser(baseIntent, "Share note").apply {
                                if (baseIntent.clipData != null) {
                                    clipData = baseIntent.clipData
                                }
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            grantShareUriPermissions(context, baseIntent, attachments)
                            if (attachments.isNotEmpty()) {
                                val lifecycle = lifecycleOwner.lifecycle
                                val observer = object : LifecycleEventObserver {
                                    private var hasStopped = false

                                    override fun onStateChanged(
                                        source: LifecycleOwner,
                                        event: Lifecycle.Event
                                    ) {
                                        when (event) {
                                            Lifecycle.Event.ON_STOP -> hasStopped = true
                                            Lifecycle.Event.ON_RESUME -> if (hasStopped) {
                                                cleanupSharedFiles(context, attachments)
                                                lifecycle.removeObserver(this)
                                            }
                                            Lifecycle.Event.ON_DESTROY -> {
                                                cleanupSharedFiles(context, attachments)
                                                lifecycle.removeObserver(this)
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                                lifecycle.addObserver(observer)
                                runCatching {
                                    context.startActivity(chooser)
                                }.onFailure { throwable ->
                                    lifecycle.removeObserver(observer)
                                    cleanupSharedFiles(context, attachments)
                                    val message = if (throwable is ActivityNotFoundException) {
                                        "No apps available to share note."
                                    } else {
                                        "Couldn't share note."
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                runCatching {
                                    context.startActivity(chooser)
                                }.onFailure { throwable ->
                                    val message = if (throwable is ActivityNotFoundException) {
                                        "No apps available to share note."
                                    } else {
                                        "Couldn't share note."
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share note")
                }
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
            note.event?.let { event ->
                EventDetailsCard(event, eventLocationDisplay)
                Spacer(modifier = Modifier.height(16.dp))
            }
            if (note.checklistItems != null) {
                ChecklistDetailSection(
                    noteId = note.id,
                    items = note.checklistItems,
                    onChecklistChange = onChecklistChange,
                )
            } else {
                val styledSource = remember(note.styledContent, note.content) {
                    note.styledContent ?: RichTextDocument.fromPlainText(note.content)
                }
                val contentText = styledSource.text
                val placeholderRegex = Regex("\\[\\[(image|file|link):(\\d+)]]")
                var cursor = 0
                placeholderRegex.findAll(contentText).forEach { match ->
                val start = match.range.first
                if (start > cursor) {
                    val segment = styledSource.slice(cursor, start)
                    if (segment.text.isNotEmpty()) {
                        val base = segment.toAnnotatedString()
                        val builder = AnnotatedString.Builder(base)
                        val matcher = Patterns.WEB_URL.matcher(segment.text)
                        while (matcher.find()) {
                            val url = segment.text.substring(matcher.start(), matcher.end())
                            builder.addStringAnnotation("URL", url, matcher.start(), matcher.end())
                            builder.addStyle(
                                SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline),
                                matcher.start(),
                                matcher.end()
                            )
                        }
                        val annotated = builder.toAnnotatedString()
                        ClickableText(
                            text = annotated,
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { sa ->
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sa.item))) }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                    }
                }
                val type = match.groupValues[1]
                val indexValue = match.groupValues[2].toInt()
                when (type) {
                    "image" -> {
                        note.images.getOrNull(indexValue)?.let { image ->
                            var imageBytes by remember(note.id, indexValue) { mutableStateOf<ByteArray?>(null) }
                            LaunchedEffect(note.id, indexValue, image.attachmentId, image.data) {
                                imageBytes = when {
                                    !image.data.isNullOrBlank() ->
                                        runCatching { Base64.decode(image.data, Base64.DEFAULT) }.getOrNull()
                                    !image.attachmentId.isNullOrBlank() ->
                                        withContext(Dispatchers.IO) { openAttachment(image.attachmentId) }
                                    else -> null
                                }
                            }
                            imageBytes?.let { bytes ->
                                val bitmap = remember(bytes) {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }
                                Image(
                                    bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .clickable { fullImage = bytes }
                                )
                            }
                        }
                    }
                    "file" -> {
                        note.files.getOrNull(indexValue)?.let { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clickable {
                                        scope.launch {
                                            val bytes = withContext(Dispatchers.IO) {
                                                when {
                                                    !file.data.isNullOrBlank() ->
                                                        runCatching { Base64.decode(file.data, Base64.DEFAULT) }.getOrNull()
                                                    !file.attachmentId.isNullOrBlank() -> openAttachment(file.attachmentId)
                                                    else -> null
                                                }
                                            }
                                            if (bytes != null) {
                                                val temp = File(context.cacheDir, file.name)
                                                temp.writeBytes(bytes)
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    temp
                                                )
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, file.mime)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                runCatching { context.startActivity(intent) }
                                                    .onFailure {
                                                        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                                                    }
                                            } else {
                                                Toast.makeText(context, "Couldn't open attachment", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = file.name
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(file.name)
                            }
                        }
                    }
                    "link" -> {
                        note.linkPreviews.getOrNull(indexValue)?.let { preview ->
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
                }
                cursor = match.range.last + 1
                if (cursor < contentText.length && contentText[cursor] == '\n') {
                    cursor++
                }
            }
            if (cursor < contentText.length) {
                val segment = styledSource.slice(cursor, contentText.length)
                if (segment.text.isNotEmpty()) {
                    val base = segment.toAnnotatedString()
                    val builder = AnnotatedString.Builder(base)
                    val matcher = Patterns.WEB_URL.matcher(segment.text)
                    while (matcher.find()) {
                        val url = segment.text.substring(matcher.start(), matcher.end())
                        builder.addStringAnnotation("URL", url, matcher.start(), matcher.end())
                        builder.addStyle(
                            SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline),
                            matcher.start(),
                            matcher.end()
                        )
                    }
                    val annotated = builder.toAnnotatedString()
                    ClickableText(
                        text = annotated,
                        onClick = { offset ->
                            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { sa ->
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sa.item))) }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
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
                val bitmap = remember(img) { BitmapFactory.decodeByteArray(img, 0, img.size) }
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
                            val bytesToSave = img
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

@Composable
private fun ChecklistDetailSection(
    noteId: Long,
    items: List<ChecklistItem>,
    onChecklistChange: (List<ChecklistItem>) -> Unit,
) {
    val checklistState = remember(noteId, items) {
        mutableStateListOf<ChecklistItem>().apply { addAll(items) }
    }
    LaunchedEffect(items) {
        checklistState.clear()
        checklistState.addAll(items)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        for (index in checklistState.indices) {
            val item = checklistState[index]
            ChecklistDetailRow(
                item = item,
                onCheckedChange = { checked ->
                    if (checklistState[index].isChecked != checked) {
                        checklistState[index] = item.copy(isChecked = checked)
                        onChecklistChange(checklistState.toList())
                    }
                }
            )
        }
    }
}

@Composable
private fun ChecklistDetailRow(
    item: ChecklistItem,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Checkbox(
            checked = item.isChecked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = item.text,
            style = MaterialTheme.typography.body1,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (item.isChecked) TextDecoration.LineThrough else null,
            color = if (item.isChecked) MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            else MaterialTheme.colors.onSurface,
        )
    }
}

private suspend fun prepareShareAttachments(
    context: Context,
    note: Note,
    openAttachment: suspend (String) -> ByteArray?,
): SharePreparation {
    if (note.images.isEmpty() && note.files.isEmpty()) {
        return SharePreparation(emptyList(), emptyList())
    }
    return withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "shared_attachments")
        if (directory.exists()) {
            directory.listFiles()?.forEach { runCatching { it.delete() } }
        } else if (!directory.mkdirs()) {
            return@withContext SharePreparation(
                attachments = emptyList(),
                errors = listOf("Couldn't prepare space for attachments.")
            )
        }
        val authority = "${context.packageName}.fileprovider"
        val attachments = mutableListOf<PreparedAttachment>()
        val errors = mutableListOf<String>()
        note.images.forEachIndexed { index, image ->
            runCatching {
                val bytes = when {
                    !image.data.isNullOrBlank() ->
                        runCatching { Base64.decode(image.data, Base64.DEFAULT) }.getOrNull()
                    !image.attachmentId.isNullOrBlank() -> openAttachment(image.attachmentId)
                    else -> null
                } ?: error("Missing image data")
                val file = writeAttachmentFile(directory, "image_${index + 1}.png", bytes)
                val uri = FileProvider.getUriForFile(context, authority, file)
                attachments += PreparedAttachment(file, uri, "image/png")
            }.onFailure {
                errors += "Couldn't attach image ${index + 1} for sharing."
            }
        }
        note.files.forEach { embedded ->
            runCatching {
                val bytes = when {
                    !embedded.data.isNullOrBlank() ->
                        runCatching { Base64.decode(embedded.data, Base64.DEFAULT) }.getOrNull()
                    !embedded.attachmentId.isNullOrBlank() -> openAttachment(embedded.attachmentId)
                    else -> null
                } ?: error("Missing attachment data")
                val fileName = sanitizeFilename(embedded.name).ifBlank { "attachment" }
                val file = writeAttachmentFile(directory, fileName, bytes)
                val uri = FileProvider.getUriForFile(context, authority, file)
                attachments += PreparedAttachment(file, uri, embedded.mime.ifBlank { "*/*" })
            }.onFailure {
                errors += "Couldn't attach ${embedded.name} for sharing."
            }
        }
        SharePreparation(attachments, errors)
    }
}

private fun writeAttachmentFile(directory: File, baseName: String, bytes: ByteArray): File {
    val sanitized = sanitizeFilename(baseName).ifBlank { "attachment" }
    val target = uniqueFile(directory, sanitized)
    target.outputStream().use { stream ->
        stream.write(bytes)
    }
    return target
}

private fun buildShareIntent(
    context: Context,
    note: Note,
    shareText: String,
    attachments: List<PreparedAttachment>,
): Intent {
    val multiple = attachments.size > 1
    val intent = Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        if (note.title.isNotBlank()) {
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            putExtra(Intent.EXTRA_TITLE, note.title)
        }
        if (shareText.isNotBlank()) {
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        type = when {
            attachments.isEmpty() -> "text/plain"
            multiple -> resolveShareMimeType(attachments)
            else -> attachments.first().mimeType.ifBlank { "*/*" }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (attachments.isNotEmpty()) {
        val streams = ArrayList<Uri>(attachments.size)
        attachments.forEach { attachment ->
            streams += attachment.uri
        }
        if (multiple) {
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
        } else {
            intent.putExtra(Intent.EXTRA_STREAM, streams.first())
        }
        val clipLabel = note.title.ifBlank { "attachments" }
        val clipData = ClipData.newUri(context.contentResolver, clipLabel, streams.first())
        streams.drop(1).forEach { uri ->
            clipData.addItem(ClipData.Item(uri))
        }
        intent.clipData = clipData
    }
    return intent
}

internal fun resolveShareMimeTypeFromMimeTypes(mimeTypes: List<String>): String {
    if (mimeTypes.isEmpty()) {
        return "text/plain"
    }
    val sanitizedTypes = mimeTypes.map { type ->
        type.takeIf { it.isNotBlank() } ?: "*/*"
    }
    if (sanitizedTypes.all { it == "*/*" }) {
        return "*/*"
    }
    if (sanitizedTypes.toSet().size == 1) {
        return sanitizedTypes.first()
    }
    val primaryTypes = sanitizedTypes.mapNotNull { type ->
        type.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotBlank() }
    }.toSet()
    return if (primaryTypes.size == 1) {
        "${primaryTypes.first()}/*"
    } else {
        "*/*"
    }
}

private fun resolveShareMimeType(attachments: List<PreparedAttachment>): String {
    return resolveShareMimeTypeFromMimeTypes(attachments.map { attachment -> attachment.mimeType })
}

private fun buildShareText(
    note: Note,
    eventLocationDisplay: EventLocationDisplay?,
): String {
    val eventSummary = note.event
        ?.let { buildEventSummary(it, eventLocationDisplay) }
        ?.takeIf { it.isNotBlank() }
    val cleanedContent = attachmentPlaceholderRegex.replace(note.content, "").trim()
    val sections = mutableListOf<String>()
    if (cleanedContent.isNotBlank()) {
        sections += cleanedContent
    }
    eventSummary?.let { sections += it }
    if (sections.isEmpty() && note.title.isNotBlank() && note.images.isEmpty() && note.files.isEmpty()) {
        sections += note.title.trim()
    }
    return sections.joinToString(separator = "\n\n").trim()
}

private fun cleanupSharedFiles(context: Context, attachments: List<PreparedAttachment>) {
    attachments.forEach { attachment ->
        runCatching {
            context.revokeUriPermission(attachment.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            if (attachment.file.exists()) {
                attachment.file.delete()
            }
        }
    }
}

private fun grantShareUriPermissions(
    context: Context,
    intent: Intent,
    attachments: List<PreparedAttachment>,
) {
    if (attachments.isEmpty()) {
        return
    }
    val matches = context.packageManager.queryIntentActivities(
        intent,
        PackageManager.MATCH_DEFAULT_ONLY
    )
    matches.forEach { resolveInfo ->
        val packageName = resolveInfo.activityInfo.packageName
        attachments.forEach { attachment ->
            runCatching {
                context.grantUriPermission(
                    packageName,
                    attachment.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }
}

private fun buildEventSummary(
    event: NoteEvent,
    locationDisplay: EventLocationDisplay?,
): String {
    val zoneId = runCatching { ZoneId.of(event.timeZone) }.getOrDefault(ZoneId.systemDefault())
    val start = Instant.ofEpochMilli(event.start).atZone(zoneId).truncatedTo(ChronoUnit.MINUTES)
    val end = Instant.ofEpochMilli(event.end).atZone(zoneId).truncatedTo(ChronoUnit.MINUTES)
    val zoneCode = formatZoneCode(zoneId, Locale.getDefault(), start.toInstant())
    return buildString {
        appendLine("Event details:")
        if (event.allDay) {
            val startDate = start.toLocalDate()
            val endDateInclusive = end.toLocalDate()
            if (endDateInclusive.isBefore(startDate) || endDateInclusive.isEqual(startDate)) {
                appendLine("All-day on ${detailDateFormatter.format(start)} ($zoneCode)")
            } else {
                appendLine(
                    "All-day from ${detailDateFormatter.format(start)} to ${detailDateFormatter.format(endDateInclusive)} ($zoneCode)"
                )
            }
        } else {
            val sameDay = start.toLocalDate() == end.toLocalDate()
            if (sameDay) {
                appendLine(detailDateFormatter.format(start))
                appendLine("${detailTimeFormatter.format(start)} – ${detailTimeFormatter.format(end)} $zoneCode")
            } else {
                appendLine("Starts: ${detailDateFormatter.format(start)} ${detailTimeFormatter.format(start)} ($zoneCode)")
                appendLine("Ends: ${detailDateFormatter.format(end)} ${detailTimeFormatter.format(end)} ($zoneCode)")
            }
        }
        event.location?.takeIf { it.isNotBlank() }?.let { location ->
            val fallback = fallbackEventLocationDisplay(location)
            val name = locationDisplay?.name?.takeIf { it.isNotBlank() }
                ?: fallback.name.takeIf { it.isNotBlank() }
                ?: location.trim()
            appendLine("Location: $name")
            val address = locationDisplay?.address?.takeUnless { it.isNullOrBlank() }
                ?: fallback.address?.takeUnless { it.isNullOrBlank() }
            address
                ?.takeUnless { it.equals(name, ignoreCase = true) }
                ?.let { appendLine(it) }
        }
        event.alarmMinutesBeforeStart?.let { minutes ->
            appendLine("Alarm: ${formatReminderOffsetMinutes(minutes)}")
        }
        event.notificationMinutesBeforeStart?.let { minutes ->
            appendLine("Reminder: ${formatReminderOffsetMinutes(minutes)}")
        }
        append("Time zone: $zoneCode (${zoneId.id})")
    }.trim()
}

private fun sanitizeFilename(name: String): String {
    val trimmed = name.substringAfterLast('/').substringAfterLast('\\')
    return trimmed.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

private fun uniqueFile(directory: File, baseName: String): File {
    var candidate = File(directory, baseName)
    if (!candidate.exists()) {
        return candidate
    }
    val dotIndex = baseName.lastIndexOf('.')
    val namePart = if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName
    val extension = if (dotIndex > 0) baseName.substring(dotIndex) else ""
    var index = 1
    while (candidate.exists()) {
        val nextName = "${namePart}_${index}${extension}"
        candidate = File(directory, nextName)
        index++
    }
    return candidate
}

private val attachmentPlaceholderRegex = Regex("\\[\\[(image|file):\\d+]]")

private data class PreparedAttachment(
    val file: File,
    val uri: Uri,
    val mimeType: String,
)

private data class SharePreparation(
    val attachments: List<PreparedAttachment>,
    val errors: List<String>,
)

private val detailDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
private val detailTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun EventDetailsCard(
    event: NoteEvent,
    locationDisplay: EventLocationDisplay?,
) {
    val zoneId = remember(event.timeZone) {
        runCatching { ZoneId.of(event.timeZone) }.getOrDefault(ZoneId.systemDefault())
    }
    val start = remember(event.start, zoneId) {
        Instant.ofEpochMilli(event.start).atZone(zoneId).truncatedTo(ChronoUnit.MINUTES)
    }
    val end = remember(event.end, zoneId) {
        Instant.ofEpochMilli(event.end).atZone(zoneId).truncatedTo(ChronoUnit.MINUTES)
    }
    val zoneCode = remember(zoneId, start) {
        formatZoneCode(zoneId, Locale.getDefault(), start.toInstant())
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Event details", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))
            if (event.allDay) {
                val startDate = start.toLocalDate()
                val endDateInclusive = end.toLocalDate()
                if (endDateInclusive.isBefore(startDate) || endDateInclusive.isEqual(startDate)) {
                    Text("All-day on ${detailDateFormatter.format(start)} ($zoneCode)")
                } else {
                    Text(
                        "All-day from ${detailDateFormatter.format(start)} to ${detailDateFormatter.format(endDateInclusive)} ($zoneCode)"
                    )
                }
            } else {
                val sameDay = start.toLocalDate() == end.toLocalDate()
                if (sameDay) {
                    Text("${detailDateFormatter.format(start)}")
                    Text("${detailTimeFormatter.format(start)} – ${detailTimeFormatter.format(end)} $zoneCode")
                } else {
                    Text("Starts: ${detailDateFormatter.format(start)} ${detailTimeFormatter.format(start)} ($zoneCode)")
                    Text("Ends: ${detailDateFormatter.format(end)} ${detailTimeFormatter.format(end)} ($zoneCode)")
                }
            }
            event.location?.takeIf { it.isNotBlank() }?.let { location ->
                val display = locationDisplay ?: fallbackEventLocationDisplay(location)
                Spacer(modifier = Modifier.height(8.dp))
                val name = display.name.takeIf { it.isNotBlank() } ?: location.trim()
                Text("Location: $name")
                val secondary = display.address
                    ?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
                secondary?.let { address ->
                    Text(address)
                }
            }
            event.alarmMinutesBeforeStart?.let { minutes ->
                Spacer(modifier = Modifier.height(8.dp))
                Text("Alarm: ${formatReminderOffsetMinutes(minutes)}")
            }
            event.notificationMinutesBeforeStart?.let { minutes ->
                Spacer(modifier = Modifier.height(8.dp))
                Text("Reminder: ${formatReminderOffsetMinutes(minutes)}")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Time zone: $zoneCode (${zoneId.id})")
        }
    }
}
