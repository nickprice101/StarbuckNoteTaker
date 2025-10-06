package com.example.starbucknotetaker

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.Patterns
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.URL
import kotlinx.coroutines.launch
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import com.example.starbucknotetaker.ChecklistItem
import com.example.starbucknotetaker.asChecklistContent
import com.example.starbucknotetaker.richtext.StyleRange
import com.example.starbucknotetaker.richtext.RichTextDocument
import com.example.starbucknotetaker.richtext.RichTextStyle

/**
 * ViewModel storing notes in memory.
 */
class NoteViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _notes = mutableStateListOf<Note>()
    val notes: List<Note> = _notes

    private var untitledCounter = 1
    private var debugNoteCounter = 1
    private var pin: String? = null
    private var store: EncryptedNoteStore? = null
    private var attachmentStore: AttachmentStore? = null
    private var context: Context? = null
    private var summarizer: Summarizer? = null
    private var reminderScheduler: NoteAlarmScheduler? = null
    private val _summarizerState = MutableStateFlow<Summarizer.SummarizerState>(Summarizer.SummarizerState.Ready)
    val summarizerState: StateFlow<Summarizer.SummarizerState> = _summarizerState
    private val unlockedNoteIds = mutableStateListOf<Long>()
    private val reminderNavigationEvents = MutableSharedFlow<Long>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val reminderNavigation: SharedFlow<Long> = reminderNavigationEvents.asSharedFlow()
    private val _biometricUnlockEvents = MutableSharedFlow<BiometricUnlockRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val biometricUnlockEvents: SharedFlow<BiometricUnlockRequest> = _biometricUnlockEvents.asSharedFlow()
    private var pendingReminderNoteId: Long? = null
    private val _pendingShare = MutableStateFlow<PendingShare?>(null)
    val pendingShare: StateFlow<PendingShare?> = _pendingShare
    private val _biometricUnlockRequest = MutableStateFlow<BiometricUnlockRequest?>(null)
    val biometricUnlockRequest: StateFlow<BiometricUnlockRequest?> = _biometricUnlockRequest
    val pendingOpenNoteId: StateFlow<Long?> =
        savedStateHandle.getStateFlow(PENDING_OPEN_NOTE_ID_KEY, null)
    val pendingUnlockNavigationNoteId: StateFlow<Long?> =
        savedStateHandle.getStateFlow(PENDING_UNLOCK_NAVIGATION_NOTE_ID_KEY, null)

    fun loadNotes(context: Context, pin: String) {
        this.pin = pin
        val appContext = context.applicationContext
        this.context = appContext
        val attachments = AttachmentStore(appContext)
        attachmentStore = attachments
        val s = EncryptedNoteStore(context, attachments)
        store = s
        reminderScheduler = NoteAlarmScheduler(appContext)
        _notes.clear()
        _notes.addAll(s.loadNotes(pin))
        unlockedNoteIds.clear()
        reorderNotes()
        reminderScheduler?.syncNotes(_notes)
        tryEmitPendingReminder()
        summarizer = Summarizer(context.applicationContext).also { sum ->
            viewModelScope.launch {
                sum.state.collect { state ->
                    _summarizerState.value = state
                    this@NoteViewModel.context?.let { ctx ->
                        when (state) {
                            Summarizer.SummarizerState.Ready -> Unit
                            Summarizer.SummarizerState.Fallback -> Unit
                            is Summarizer.SummarizerState.Error ->
                                NotificationInterruptionManager.runOrQueue {
                                    Toast.makeText(
                                        ctx,
                                        "Summarizer init failed: ${'$'}{state.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    fun setPendingShare(pending: PendingShare?) {
        _pendingShare.value = pending
    }

    fun clearPendingShare() {
        _pendingShare.value = null
    }

    fun requestBiometricUnlock(noteId: Long, title: String) {
        val request = BiometricUnlockRequest(noteId, title)
        _biometricUnlockRequest.value = request
        val emitted = _biometricUnlockEvents.tryEmit(request)
        if (emitted) {
            Log.d(
                BIOMETRIC_LOG_TAG,
                "requestBiometricUnlock noteId=${'$'}noteId title=\"${'$'}title\" token=${'$'}{request.token} delivery=delivered"
            )
        } else {
            Log.d(
                BIOMETRIC_LOG_TAG,
                "requestBiometricUnlock noteId=${'$'}noteId title=\"${'$'}title\" token=${'$'}{request.token} delivery=buffer_overflow"
            )
        }
    }

    fun clearBiometricUnlockRequest() {
        _biometricUnlockRequest.value = null
    }

    fun currentBiometricUnlockRequest(): BiometricUnlockRequest? {
        return _biometricUnlockRequest.value
    }

    fun setPendingOpenNoteId(noteId: Long) {
        savedStateHandle[PENDING_OPEN_NOTE_ID_KEY] = noteId
    }

    fun clearPendingOpenNoteId() {
        savedStateHandle[PENDING_OPEN_NOTE_ID_KEY] = null
    }

    fun setPendingUnlockNavigationNoteId(noteId: Long) {
        savedStateHandle[PENDING_UNLOCK_NAVIGATION_NOTE_ID_KEY] = noteId
    }

    fun clearPendingUnlockNavigationNoteId() {
        savedStateHandle[PENDING_UNLOCK_NAVIGATION_NOTE_ID_KEY] = null
    }

    private companion object {
        const val PENDING_OPEN_NOTE_ID_KEY = "pending_open_note_id"
        const val PENDING_UNLOCK_NAVIGATION_NOTE_ID_KEY = "pending_unlock_navigation_note_id"
    }

    fun updateChecklistItems(id: Long, items: List<ChecklistItem>) {
        val note = getNoteById(id) ?: return
        val normalized = normalizeChecklistItems(items)
        val content = normalized.asChecklistContent()
        val styled = RichTextDocument.fromPlainText(content)
        updateNote(
            id = id,
            title = note.title,
            content = content,
            styledContent = styled,
            images = note.images,
            files = note.files,
            linkPreviews = note.linkPreviews,
            event = note.event,
            checklistItems = normalized,
        )
    }

    fun addNote(
        title: String?,
        content: String,
        styledContent: RichTextDocument,
        images: List<NewNoteImage>,
        files: List<Uri>,
        linkPreviews: List<NoteLinkPreview>,
        event: NoteEvent? = null,
        checklistItems: List<ChecklistItem>? = null,
    ) {
        val finalTitle = if (title.isNullOrBlank()) {
            "Untitled ${untitledCounter++}"
        } else {
            title
        }
        val normalizedChecklist = checklistItems?.let(::normalizeChecklistItems)
        val checklistContent = normalizedChecklist?.asChecklistContent()
        val contentForProcessing = checklistContent ?: content
        val styledForProcessing =
            if (normalizedChecklist != null) {
                RichTextDocument.fromPlainText(contentForProcessing)
            } else {
                styledContent
            }
        val processed = processNewNoteContent(contentForProcessing, styledForProcessing, images, files)
        val finalContent = processed.text
        val finalStyled = processed.styled
        val embeddedImages = processed.images
        val embeddedFiles = processed.files
        val summarizerSource = buildSummarizerSource(finalTitle, finalContent, event)
        val initialSummary = synchronousSummaryPlaceholder(summarizerSource)
        val note = Note(
            title = finalTitle,
            content = finalContent.trim(),
            styledContent = finalStyled.trimmed(),
            date = event?.start ?: System.currentTimeMillis(),
            images = embeddedImages,
            files = embeddedFiles,
            linkPreviews = linkPreviews,
            summary = initialSummary,
            event = event,
            checklistItems = normalizedChecklist,
        )
        _notes.add(note)
        reorderNotes()
        pin?.let { store?.saveNotes(_notes, it) }
        reminderScheduler?.scheduleIfNeeded(note)
        tryEmitPendingReminder()
        val noteId = note.id
        launchSummaryUpdates(noteId, finalTitle, summarizerSource, event)
    }

    fun deleteNote(id: Long) {
        val index = _notes.indexOfFirst { it.id == id }
        if (index != -1) {
            val note = _notes[index]
            reminderScheduler?.cancel(id)
            deleteAttachmentsFor(note)
            _notes.removeAt(index)
            unlockedNoteIds.remove(id)
            if (pendingReminderNoteId == id) {
                pendingReminderNoteId = null
            }
            pin?.let { store?.saveNotes(_notes, it) }
        }
    }

    fun restoreNote(note: Note) {
        if (_notes.any { it.id == note.id }) {
            return
        }
        _notes.add(note)
        reorderNotes()
        pin?.let { store?.saveNotes(_notes, it) }
        reminderScheduler?.scheduleIfNeeded(note)
        tryEmitPendingReminder()
    }

    fun updateNote(
        id: Long,
        title: String?,
        content: String,
        styledContent: RichTextDocument,
        images: List<NoteImage>,
        files: List<NoteFile>,
        linkPreviews: List<NoteLinkPreview>,
        event: NoteEvent? = null,
        checklistItems: List<ChecklistItem>? = null,
    ) {
        val index = _notes.indexOfFirst { it.id == id }
        if (index != -1) {
            val note = _notes[index]
            val finalTitle = if (title.isNullOrBlank()) note.title else title
            val finalEvent = event ?: note.event
            val normalizedChecklist = checklistItems?.let(::normalizeChecklistItems)
            val finalChecklistItems = normalizedChecklist ?: note.checklistItems
            val contentForUpdate = normalizedChecklist?.asChecklistContent() ?: content
            val styledForUpdate =
                if (normalizedChecklist != null) {
                    RichTextDocument.fromPlainText(contentForUpdate)
                } else {
                    styledContent
                }
            val summarizerSource = buildSummarizerSource(finalTitle, contentForUpdate, finalEvent)
            val initialSummary = synchronousSummaryPlaceholder(summarizerSource)
            val updatedDate = finalEvent?.start ?: System.currentTimeMillis()
            val preparedImages = prepareImagesForStorage(images)
            val preparedFiles = prepareFilesForStorage(files)
            val removedImageIds = note.images.mapNotNull { it.attachmentId }.toSet() -
                    preparedImages.mapNotNull { it.attachmentId }.toSet()
            val removedFileIds = note.files.mapNotNull { it.attachmentId }.toSet() -
                    preparedFiles.mapNotNull { it.attachmentId }.toSet()
            val updated = note.copy(
                title = finalTitle,
                content = contentForUpdate.trim(),
                styledContent = styledForUpdate.trimmed(),
                images = preparedImages,
                files = preparedFiles,
                linkPreviews = linkPreviews,
                summary = initialSummary,
                event = finalEvent,
                date = updatedDate,
                checklistItems = finalChecklistItems,
            )
            _notes[index] = updated
            reorderNotes()
            pin?.let { store?.saveNotes(_notes, it) }
            deleteAttachments(removedImageIds + removedFileIds)
            reminderScheduler?.scheduleIfNeeded(updated)
            tryEmitPendingReminder()
            val noteId = updated.id
            launchSummaryUpdates(noteId, finalTitle, summarizerSource, finalEvent)
        }
    }

    fun getNoteById(id: Long): Note? = _notes.firstOrNull { it.id == id }

    fun isNoteTemporarilyUnlocked(id: Long): Boolean = unlockedNoteIds.contains(id)

    fun markNoteTemporarilyUnlocked(id: Long) {
        if (!unlockedNoteIds.contains(id)) {
            unlockedNoteIds.add(id)
        }
    }

    fun relockNote(id: Long) {
        unlockedNoteIds.remove(id)
    }

    fun setNoteLock(id: Long, locked: Boolean) {
        val index = _notes.indexOfFirst { it.id == id }
        if (index != -1) {
            val updated = _notes[index].copy(isLocked = locked)
            _notes[index] = updated
            if (!locked) {
                unlockedNoteIds.remove(id)
            }
            pin?.let { store?.saveNotes(_notes, it) }
            reminderScheduler?.scheduleIfNeeded(updated)
        }
    }

    fun updateStoredPin(newPin: String) {
        val oldPin = pin ?: return
        val attachments = attachmentStore
        if (attachments != null) {
            val ids = _notes.flatMap { note ->
                note.images.mapNotNull { it.attachmentId } + note.files.mapNotNull { it.attachmentId }
            }.toSet()
            ids.forEach { id ->
                runCatching { attachments.reencryptAttachment(oldPin, newPin, id) }
            }
        }
        pin = newPin
        store?.saveNotes(_notes, newPin)
    }

    fun openAttachment(id: String): ByteArray? {
        val currentPin = pin ?: return null
        val attachments = attachmentStore ?: return null
        return attachments.openAttachment(currentPin, id)
    }

    private fun prepareImagesForStorage(images: List<NoteImage>): List<NoteImage> {
        return images.map { image ->
            val data = image.data
            if (!data.isNullOrBlank()) {
                val attachments = attachmentStore
                val currentPin = pin
                if (attachments != null && currentPin != null) {
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
                    if (bytes != null) {
                        val id = runCatching {
                            attachments.saveAttachment(currentPin, bytes, image.attachmentId)
                        }.getOrNull()
                        if (id != null) {
                            return@map image.copy(attachmentId = id, data = null)
                        }
                    }
                }
                return@map image
            }
            if (image.data?.isBlank() == true) {
                image.copy(data = null)
            } else {
                image
            }
        }
    }

    private fun prepareFilesForStorage(files: List<NoteFile>): List<NoteFile> {
        return files.map { file ->
            val data = file.data
            if (!data.isNullOrBlank()) {
                val attachments = attachmentStore
                val currentPin = pin
                if (attachments != null && currentPin != null) {
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
                    if (bytes != null) {
                        val id = runCatching {
                            attachments.saveAttachment(currentPin, bytes, file.attachmentId)
                        }.getOrNull()
                        if (id != null) {
                            return@map file.copy(attachmentId = id, data = null)
                        }
                    }
                }
                return@map file
            }
            if (file.data?.isBlank() == true) {
                file.copy(data = null)
            } else {
                file
            }
        }
    }

    private fun deleteAttachments(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val attachments = attachmentStore ?: return
        ids.forEach { id -> runCatching { attachments.deleteAttachment(id) } }
    }

    private fun deleteAttachmentsFor(note: Note) {
        val ids = buildSet {
            note.images.mapNotNullTo(this) { it.attachmentId }
            note.files.mapNotNullTo(this) { it.attachmentId }
        }
        deleteAttachments(ids)
    }

    private fun normalizeChecklistItems(items: List<ChecklistItem>): List<ChecklistItem> {
        return items.mapNotNull { item ->
            val text = item.text.trim()
            if (text.isEmpty()) {
                null
            } else {
                ChecklistItem(text = text, isChecked = item.isChecked)
            }
        }
    }

    private fun processNewNoteContent(
        content: String,
        styledContent: RichTextDocument,
        images: List<NewNoteImage>,
        files: List<Uri>,
    ): ProcessedNoteContent {
        val embeddedImages = mutableListOf<NoteImage>()
        val embeddedFiles = mutableListOf<NoteFile>()
        val store = attachmentStore
        val currentPin = pin
        var styledText = styledContent.text
        val styledChars = styledContent.toCharacterStyles().map { it.toMutableSet() }.toMutableList()
        fun insertPlaceholder(start: Int, originalLength: Int, replacement: String) {
            if (start < 0) return
            if (start > styledChars.size) return
            repeat(originalLength.coerceAtMost((styledChars.size - start).coerceAtLeast(0))) {
                if (start < styledChars.size) styledChars.removeAt(start)
            }
            replacement.forEachIndexed { index, _ ->
                styledChars.add(start + index, mutableSetOf())
            }
            styledText = styledText.replaceRange(start, start + originalLength, replacement)
        }
        val ctx = context
        images.forEach { image ->
            val pngBytes = when {
                image.data?.isNotBlank() == true -> {
                    val decoded = runCatching { Base64.decode(image.data, Base64.DEFAULT) }.getOrNull()
                    if (decoded != null) {
                        try {
                            var bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                            if (bitmap != null) {
                                val normalizedRotation = ((image.rotation % 360) + 360) % 360
                                if (normalizedRotation != 0) {
                                    val matrix = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
                                    bitmap = Bitmap.createBitmap(
                                        bitmap,
                                        0,
                                        0,
                                        bitmap.width,
                                        bitmap.height,
                                        matrix,
                                        true
                                    )
                                }
                                val baos = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                                baos.toByteArray()
                            } else {
                                null
                            }
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }
                image.uri != null && ctx != null -> {
                    try {
                        ctx.contentResolver.openInputStream(image.uri)?.use { input ->
                            val bytes = input.readBytes()
                            val exif = ExifInterface(ByteArrayInputStream(bytes))
                            val orientation = exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL
                            )
                            val exifRotation = when (orientation) {
                                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                else -> 0
                            }
                            val totalRotation = (exifRotation + image.rotation) % 360
                            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (totalRotation != 0) {
                                val matrix = Matrix().apply { postRotate(totalRotation.toFloat()) }
                                bitmap = Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    0,
                                    bitmap.width,
                                    bitmap.height,
                                    matrix,
                                    true
                                )
                            }
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                            baos.toByteArray()
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                else -> null
            }
            pngBytes?.let { bytes ->
                val id = if (store != null && currentPin != null) {
                    runCatching { store.saveAttachment(currentPin, bytes) }.getOrNull()
                } else {
                    null
                }
                embeddedImages.add(
                    if (id != null) {
                        NoteImage(attachmentId = id)
                    } else {
                        NoteImage(data = Base64.encodeToString(bytes, Base64.DEFAULT))
                    }
                )
            }
        }
        ctx?.let { context ->
            files.forEach { uri ->
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else "file"
                        } ?: "file"
                        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        val id = if (store != null && currentPin != null) {
                            runCatching { store.saveAttachment(currentPin, bytes) }.getOrNull()
                        } else {
                            null
                        }
                        embeddedFiles.add(
                            NoteFile(
                                name = name,
                                mime = mime,
                                attachmentId = id,
                                data = if (id == null) Base64.encodeToString(bytes, Base64.DEFAULT) else null,
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        var finalContent = content
        val urlRegex = Regex("(https?://\\S+)")
        var searchStart = 0
        urlRegex.findAll(content).forEach { match ->
            val url = match.value
            if (isImageUrl(url)) {
                try {
                    val bytes = URL(url).readBytes()
                    val id = if (store != null && currentPin != null) {
                        runCatching { store.saveAttachment(currentPin, bytes) }.getOrNull()
                    } else {
                        null
                    }
                    embeddedImages.add(
                        if (id != null) {
                            NoteImage(attachmentId = id)
                        } else {
                            NoteImage(data = Base64.encodeToString(bytes, Base64.DEFAULT))
                        }
                    )
                    val idx = embeddedImages.size - 1
                    val placeholder = "[[image:$idx]]"
                    finalContent = finalContent.replaceFirst(url, placeholder)
                    var indexInStyled = styledText.indexOf(url, searchStart)
                    if (indexInStyled == -1) {
                        indexInStyled = styledText.indexOf(url)
                    }
                    if (indexInStyled != -1) {
                        insertPlaceholder(indexInStyled, url.length, placeholder)
                        searchStart = indexInStyled + placeholder.length
                    } else {
                        searchStart = 0
                    }
                } catch (_: Exception) {}
            }
        }

        while (styledChars.size < styledText.length) {
            styledChars.add(mutableSetOf())
        }
        if (styledChars.size > styledText.length) {
            while (styledChars.size > styledText.length) {
                styledChars.removeAt(styledChars.lastIndex)
            }
        }

        return ProcessedNoteContent(
            text = finalContent,
            styled = buildRichTextDocument(styledText, styledChars),
            images = embeddedImages,
            files = embeddedFiles,
        )
    }

    private fun buildRichTextDocument(
        text: String,
        characterStyles: List<MutableSet<RichTextStyle>>,
    ): RichTextDocument {
        if (text.isEmpty()) return RichTextDocument("")
        val spans = mutableListOf<StyleRange>()
        var index = 0
        while (index < text.length && index < characterStyles.size) {
            val styles = characterStyles[index].toSet()
            if (styles.isNotEmpty()) {
                var end = index + 1
                while (end < text.length && end < characterStyles.size && characterStyles[end].toSet() == styles) {
                    end++
                }
                spans.add(StyleRange(index, end, styles))
                index = end
            } else {
                index++
            }
        }
        return RichTextDocument(text, spans)
    }

    private fun buildSummarizerSource(title: String, content: String, event: NoteEvent?): String {
        val trimmedTitle = title.trim()
        val trimmedContent = content.trim()
        if (event == null) {
            return buildString {
                if (trimmedTitle.isNotEmpty()) {
                    append("Title: ")
                    append(trimmedTitle)
                }
                if (trimmedContent.isNotEmpty()) {
                    if (length > 0) append("\n\n")
                    append(trimmedContent)
                }
            }.trim()
        }
        if (trimmedContent.isEmpty()) {
            return ""
        }
        return buildString {
            if (trimmedTitle.isNotEmpty()) {
                append("Title: ")
                append(trimmedTitle)
                append("\n\n")
            }
            append(trimmedContent)
        }.trim()
    }

    private fun synchronousSummaryPlaceholder(source: String): String {
        if (source.isBlank()) return ""
        val sum = summarizer
        return if (sum != null) {
            sum.quickFallbackSummary(source)
        } else {
            Summarizer.lightweightPreview(source)
        }
    }

    private fun launchSummaryUpdates(
        noteId: Long,
        noteTitle: String,
        source: String,
        event: NoteEvent?,
    ) {
        if (source.isBlank()) return
        val sum = summarizer ?: return
        viewModelScope.launch {
            runCatching {
                sum.fallbackSummary(source, event)
            }.onSuccess { fallback ->
                updateNoteSummary(noteId, fallback)
            }.onFailure {
                Log.e("NoteViewModel", "fallback summary failed", it)
            }

            runCatching {
                sum.summarize(source)
            }.onSuccess { summary ->
                val trace = sum.consumeDebugTrace()
                val updated = updateNoteSummary(noteId, summary)
                if (updated && shouldCreateDebugNote(trace)) {
                    createSummarizerDebugNote(noteTitle, source, trace)
                }
            }.onFailure {
                Log.e("NoteViewModel", "summarizer inference failed", it)
            }
        }
    }

    private fun updateNoteSummary(noteId: Long, summary: String): Boolean {
        val index = _notes.indexOfFirst { it.id == noteId }
        if (index == -1) return false
        if (_notes[index].summary == summary) {
            return true
        }
        _notes[index] = _notes[index].copy(summary = summary)
        pin?.let { store?.saveNotes(_notes, it) }
        return true
    }

    private data class ProcessedNoteContent(
        val text: String,
        val styled: RichTextDocument,
        val images: List<NoteImage>,
        val files: List<NoteFile>,
    )

    fun exportNotes(context: Context, uri: Uri) {
        val currentPin = pin ?: return
        store?.saveNotes(_notes, currentPin)
        val src = File(context.filesDir, "notes.enc")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                src.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(context, "Archive saved", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Failed to save archive", Toast.LENGTH_SHORT).show()
        }
    }

    fun importNotes(context: Context, uri: Uri, archivePin: String, overwrite: Boolean): Boolean {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: return false
            val attachments = attachmentStore ?: AttachmentStore(context.applicationContext)
            val imported = EncryptedNoteStore(context, attachments).loadNotesFromBytes(bytes, archivePin)
            if (overwrite) {
                _notes.forEach { deleteAttachmentsFor(it) }
                _notes.clear()
            }
            _notes.addAll(imported)
            reorderNotes()
            pin?.let { store?.saveNotes(_notes, it) }
            reminderScheduler?.syncNotes(_notes)
            tryEmitPendingReminder()
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun onCleared() {
        summarizer?.close()
        super.onCleared()
    }
    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return Patterns.WEB_URL.matcher(url).matches() &&
                (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                        lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp"))
    }

    private fun shouldCreateDebugNote(trace: List<String>): Boolean {
        return trace.any { it.startsWith("fallback reason:") }
    }

    private fun createSummarizerDebugNote(relatedTitle: String, source: String, trace: List<String>) {
        if (trace.isEmpty()) return
        val reason = trace.firstOrNull { it.startsWith("fallback reason:") }
            ?.removePrefix("fallback reason:")
            ?.trim()
        val sanitizedRelatedTitle = relatedTitle
            .lines()
            .firstOrNull()
            ?.take(60)
            ?.trim()
            .orEmpty()
        val referencedTitle = if (sanitizedRelatedTitle.isNotBlank()) {
            sanitizedRelatedTitle
        } else {
            "Related entry"
        }
        val content = buildString {
            appendLine("Summarizer fallback triggered for \"$referencedTitle\".")
            if (!reason.isNullOrBlank()) {
                appendLine("Reason: $reason")
            }
            if (source.isNotBlank()) {
                appendLine()
                appendLine("Source excerpt:")
                appendLine(source.take(500))
            }
            appendLine()
            appendLine("Debug trace:")
            trace.forEach { appendLine("• $it") }
        }.trim()
        val summaryText = reason?.takeIf { it.isNotBlank() } ?: "Summarizer fallback triggered"
        val note = Note(
            title = "Summarizer Debug – $referencedTitle (#${debugNoteCounter++})",
            content = content,
            summary = summaryText.take(200),
            date = System.currentTimeMillis(),
        )
        _notes.add(note)
        reorderNotes()
        pin?.let { store?.saveNotes(_notes, it) }
    }

    private fun reorderNotes() {
        if (_notes.size <= 1) {
            return
        }
        val sorted = _notes.sortedWith(
            compareByDescending<Note> { it.date }
                .thenByDescending { it.id }
        )
        if (!sorted.zip(_notes).all { it.first === it.second }) {
            _notes.clear()
            _notes.addAll(sorted)
        }
    }

    fun handleReminderNavigation(noteId: Long) {
        if (_notes.any { it.id == noteId }) {
            emitReminderNavigation(noteId)
        } else {
            pendingReminderNoteId = noteId
        }
    }

    private fun emitReminderNavigation(noteId: Long) {
        viewModelScope.launch {
            reminderNavigationEvents.emit(noteId)
        }
    }

    private fun tryEmitPendingReminder() {
        val id = pendingReminderNoteId ?: return
        if (_notes.any { it.id == id }) {
            pendingReminderNoteId = null
            emitReminderNavigation(id)
        }
    }
}

data class PendingShare(
    val title: String?,
    val text: String?,
    val images: List<Uri>,
    val files: List<Uri>,
)
