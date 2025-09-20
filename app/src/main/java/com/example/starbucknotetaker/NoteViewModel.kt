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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.URL
import kotlinx.coroutines.launch
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

/**
 * ViewModel storing notes in memory.
 */
class NoteViewModel : ViewModel() {
    private val _notes = mutableStateListOf<Note>()
    val notes: List<Note> = _notes

    private var untitledCounter = 1
    private var debugNoteCounter = 1
    private var pin: String? = null
    private var store: EncryptedNoteStore? = null
    private var context: Context? = null
    private var summarizer: Summarizer? = null
    private val _summarizerState = MutableStateFlow<Summarizer.SummarizerState>(Summarizer.SummarizerState.Ready)
    val summarizerState: StateFlow<Summarizer.SummarizerState> = _summarizerState
    private val unlockedNoteIds = mutableStateListOf<Long>()

    fun loadNotes(context: Context, pin: String) {
        this.pin = pin
        this.context = context.applicationContext
        val s = EncryptedNoteStore(context)
        store = s
        _notes.clear()
        _notes.addAll(s.loadNotes(pin))
        unlockedNoteIds.clear()
        reorderNotes()
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
        val note = Note(
            title = finalTitle,
            content = finalContent.trim(),
            date = event?.start ?: System.currentTimeMillis(),
            images = embeddedImages,
            files = embeddedFiles,
            linkPreviews = linkPreviews,
            summary = summarizer?.let { it.fallbackSummary(summarizerSource) } ?: summarizerSource.take(200),
            event = event,
        )
        _notes.add(note)
        reorderNotes()
        pin?.let { store?.saveNotes(_notes, it) }
        val noteId = note.id
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

    fun deleteNote(id: Long) {
        val index = _notes.indexOfFirst { it.id == id }
        if (index != -1) {
            _notes.removeAt(index)
            unlockedNoteIds.remove(id)
            pin?.let { store?.saveNotes(_notes, it) }
        }
    }

    fun updateNote(
        id: Long,
        title: String?,
        content: String,
        images: List<String>,
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
            val updatedDate = finalEvent?.start ?: System.currentTimeMillis()
            val updated = note.copy(
                title = finalTitle,
                content = content.trim(),
                images = images,
                files = files,
                linkPreviews = linkPreviews,
                summary = summarizer?.let { it.fallbackSummary(summarizerSource) } ?: summarizerSource.take(200),
                event = finalEvent,
                date = updatedDate,
            )
            _notes[index] = updated
            reorderNotes()
            pin?.let { store?.saveNotes(_notes, it) }
            val noteId = updated.id
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
        }
    }

    fun updateStoredPin(newPin: String) {
        pin = newPin
        pin?.let { store?.saveNotes(_notes, it) }
    }

    private fun processNewNoteContent(
        content: String,
        images: List<Pair<Uri, Int>>,
        files: List<Uri>,
    ): ProcessedNoteContent {
        val embeddedImages = mutableListOf<String>()
        val embeddedFiles = mutableListOf<NoteFile>()
        context?.let { ctx ->
            images.forEach { (uri, rotation) ->
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        val exif = ExifInterface(ByteArrayInputStream(bytes))
                        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
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
                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        }
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        embeddedImages.add(Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT))
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
                        embeddedFiles.add(
                            NoteFile(
                                name = name,
                                mime = mime,
                                data = Base64.encodeToString(bytes, Base64.DEFAULT)
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
                    embeddedImages.add(Base64.encodeToString(bytes, Base64.DEFAULT))
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
        if (event == null) {
            return content
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
            if (content.isNotBlank()) {
                append('\n')
                append(content)
            }
        }
    }

    private data class ProcessedNoteContent(
        val text: String,
        val images: List<String>,
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
            val imported = EncryptedNoteStore(context).loadNotesFromBytes(bytes, archivePin)
            if (overwrite) {
                _notes.clear()
            }
            _notes.addAll(imported)
            reorderNotes()
            pin?.let { store?.saveNotes(_notes, it) }
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
            title = "Summarizer Debug – $referencedTitle (#${'$'}{debugNoteCounter++})",
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
}
