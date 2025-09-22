package com.example.starbucknotetaker

import android.content.Context
import android.net.Uri
import android.util.Base64
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asSharedFlow

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
    private var reminderScheduler: NoteReminderScheduler? = null
    private val _summarizerState = MutableStateFlow<Summarizer.SummarizerState>(Summarizer.SummarizerState.Ready)
    val summarizerState: StateFlow<Summarizer.SummarizerState> = _summarizerState
    private val unlockedNoteIds = mutableStateListOf<Long>()
    private val reminderNavigationEvents = MutableSharedFlow<Long>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val reminderNavigation: SharedFlow<Long> = reminderNavigationEvents.asSharedFlow()
    private var pendingReminderNoteId: Long? = null
    private val _pendingShare = MutableStateFlow<PendingShare?>(null)
    val pendingShare: StateFlow<PendingShare?> = _pendingShare
    private val _biometricUnlockRequest = MutableStateFlow<BiometricUnlockRequest?>(null)
    val biometricUnlockRequest: StateFlow<BiometricUnlockRequest?> = _biometricUnlockRequest
    val pendingOpenNoteId: StateFlow<Long?> =
        savedStateHandle.getStateFlow(PENDING_OPEN_NOTE_ID_KEY, null)
    val noteIdToOpenAfterUnlock: StateFlow<Long?> =
        savedStateHandle.getStateFlow(NOTE_ID_TO_OPEN_AFTER_UNLOCK_KEY, null)

    fun loadNotes(context: Context, pin: String) {
        this.pin = pin
        val appContext = context.applicationContext
        this.context = appContext
        val attachments = AttachmentStore(appContext)
        attachmentStore = attachments
        val s = EncryptedNoteStore(context, attachments)
        store = s
        reminderScheduler = NoteReminderScheduler(appContext)
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
                            Summarizer.SummarizerState.Ready ->
                                NotificationInterruptionManager.runOrQueue {
                                    Toast.makeText(ctx, "AI summarizer loaded", Toast.LENGTH_SHORT).show()
                                }
                            Summarizer.SummarizerState.Fallback ->
                                NotificationInterruptionManager.runOrQueue {
                                    Toast.makeText(ctx, "Using fallback summarization", Toast.LENGTH_SHORT).show()
                                }
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
        _biometricUnlockRequest.value = BiometricUnlockRequest(noteId, title)
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

    fun setNoteIdToOpenAfterUnlock(noteId: Long) {
        savedStateHandle[NOTE_ID_TO_OPEN_AFTER_UNLOCK_KEY] = noteId
    }

    fun clearNoteIdToOpenAfterUnlock() {
        savedStateHandle[NOTE_ID_TO_OPEN_AFTER_UNLOCK_KEY] = null
    }

    private companion object {
        const val PENDING_OPEN_NOTE_ID_KEY = "pending_open_note_id"
        const val NOTE_ID_TO_OPEN_AFTER_UNLOCK_KEY = "note_id_to_open_after_unlock"
    }

    fun addNote(
        title: String?,
        content: String,
        images: List<Pair<Uri, Int>>,
        files: List<Uri>,
        linkPreviews: List<NoteLinkPreview>,
        event: NoteEvent? = null,
    ) {
        val finalTitle = if (title.isNullOrBlank()) {
            "Untitled ${untitledCounter++}"
        } else {
            title
        }
        val processed = processNewNoteContent(content, images, files)
        val finalContent = processed.text
        val embeddedImages = processed.images
        val embeddedFiles = processed.files
        val summarizerSource = buildSummarizerSource(finalContent, event)
        val initialSummary = if (summarizerSource.isBlank()) {
            ""
        } else {
            summarizer?.let { it.fallbackSummary(summarizerSource, event) } ?: summarizerSource.take(200)
        }
        val note = Note(
            title = finalTitle,
            content = finalContent.trim(),
            date = event?.start ?: System.currentTimeMillis(),
            images = embeddedImages,
            files = embeddedFiles,
            linkPreviews = linkPreviews,
            summary = initialSummary,
            event = event,
        )
        _notes.add(note)
        reorderNotes()
        pin?.let { store?.saveNotes(_notes, it) }
        reminderScheduler?.scheduleIfNeeded(note)
        tryEmitPendingReminder()
        val noteId = note.id
        if (summarizerSource.isNotBlank()) {
            summarizer?.let { sum ->
                viewModelScope.launch {
                    val summary = sum.summarize(summarizerSource)
                    val trace = sum.consumeDebugTrace()
                    val index = _notes.indexOfFirst { it.id == noteId }
                    if (index != -1) {
                        _notes[index] = _notes[index].copy(summary = summary)
                        pin?.let { store?.saveNotes(_notes, it) }
                    }
                    if (shouldCreateDebugNote(trace)) {
                        createSummarizerDebugNote(finalTitle, summarizerSource, trace)
                    }
                }
            }
        }
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

    fun updateNote(
        id: Long,
        title: String?,
        content: String,
        images: List<NoteImage>,
        files: List<NoteFile>,
        linkPreviews: List<NoteLinkPreview>,
        event: NoteEvent? = null,
    ) {
        val index = _notes.indexOfFirst { it.id == id }
        if (index != -1) {
            val note = _notes[index]
            val finalTitle = if (title.isNullOrBlank()) note.title else title
            val finalEvent = event ?: note.event
            val summarizerSource = buildSummarizerSource(content, finalEvent)
            val initialSummary = if (summarizerSource.isBlank()) {
                ""
            } else {
                summarizer?.let { it.fallbackSummary(summarizerSource, finalEvent) } ?: summarizerSource.take(200)
            }
            val updatedDate = finalEvent?.start ?: System.currentTimeMillis()
            val preparedImages = prepareImagesForStorage(images)
            val preparedFiles = prepareFilesForStorage(files)
            val removedImageIds = note.images.mapNotNull { it.attachmentId }.toSet() -
                    preparedImages.mapNotNull { it.attachmentId }.toSet()
            val removedFileIds = note.files.mapNotNull { it.attachmentId }.toSet() -
                    preparedFiles.mapNotNull { it.attachmentId }.toSet()
            val updated = note.copy(
                title = finalTitle,
                content = content.trim(),
                images = preparedImages,
                files = preparedFiles,
                linkPreviews = linkPreviews,
                summary = initialSummary,
                event = finalEvent,
                date = updatedDate,
            )
            _notes[index] = updated
            reorderNotes()
            pin?.let { store?.saveNotes(_notes, it) }
            deleteAttachments(removedImageIds + removedFileIds)
            reminderScheduler?.scheduleIfNeeded(updated)
            tryEmitPendingReminder()
            val noteId = updated.id
            if (summarizerSource.isNotBlank()) {
                summarizer?.let { sum ->
                    viewModelScope.launch {
                        val summary = sum.summarize(summarizerSource)
                        val trace = sum.consumeDebugTrace()
                        val newIndex = _notes.indexOfFirst { it.id == noteId }
                        if (newIndex != -1) {
                            _notes[newIndex] = _notes[newIndex].copy(summary = summary)
                            pin?.let { store?.saveNotes(_notes, it) }
                        }
                        if (shouldCreateDebugNote(trace)) {
                            createSummarizerDebugNote(finalTitle, summarizerSource, trace)
                        }
                    }
                }
            }
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

    private fun processNewNoteContent(
        content: String,
        images: List<Pair<Uri, Int>>,
        files: List<Uri>,
    ): ProcessedNoteContent {
        val embeddedImages = mutableListOf<NoteImage>()
        val embeddedFiles = mutableListOf<NoteFile>()
        val store = attachmentStore
        val currentPin = pin
        context?.let { ctx ->
            images.forEach { (uri, rotation) ->
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
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
                        val totalRotation = (exifRotation + rotation) % 360
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
                        val pngBytes = baos.toByteArray()
                        val id = if (store != null && currentPin != null) {
                            runCatching { store.saveAttachment(currentPin, pngBytes) }.getOrNull()
                        } else {
                            null
                        }
                        embeddedImages.add(
                            if (id != null) {
                                NoteImage(attachmentId = id)
                            } else {
                                NoteImage(data = Base64.encodeToString(pngBytes, Base64.DEFAULT))
                            }
                        )
                    }
                } catch (_: Exception) {}
            }
            files.forEach { uri ->
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else "file"
                        } ?: "file"
                        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
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
                    finalContent = finalContent.replace(url, "[[image:$idx]]")
                } catch (_: Exception) {}
            }
        }

        return ProcessedNoteContent(
            text = finalContent,
            images = embeddedImages,
            files = embeddedFiles,
        )
    }

    private fun buildSummarizerSource(content: String, event: NoteEvent?): String {
        val trimmedContent = content.trim()
        if (event == null) {
            return trimmedContent
        }
        if (trimmedContent.isEmpty()) {
            return ""
        }
        val zoneId = runCatching { java.time.ZoneId.of(event.timeZone) }
            .getOrDefault(java.time.ZoneId.systemDefault())
        val start = java.time.Instant.ofEpochMilli(event.start).atZone(zoneId)
        val end = java.time.Instant.ofEpochMilli(event.end).atZone(zoneId)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        return buildString {
            append("Event from ")
            append(start.format(formatter))
            append(" to ")
            append(end.format(formatter))
            if (!event.location.isNullOrBlank()) {
                append(" at ")
                append(event.location)
            }
            append('\n')
            append(trimmedContent)
        }
    }

    private data class ProcessedNoteContent(
        val text: String,
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
