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
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.URL
import kotlinx.coroutines.launch

/**
 * ViewModel storing notes in memory.
 */
class NoteViewModel : ViewModel() {
    private val _notes = mutableStateListOf<Note>()
    val notes: List<Note> = _notes

    private var untitledCounter = 1
    private var pin: String? = null
    private var store: EncryptedNoteStore? = null
    private var context: Context? = null
    private var summarizer: Summarizer? = null

    fun loadNotes(context: Context, pin: String) {
        this.pin = pin
        this.context = context.applicationContext
        val s = EncryptedNoteStore(context)
        store = s
        _notes.clear()
        _notes.addAll(s.loadNotes(pin))
        summarizer = Summarizer(context.applicationContext)
    }

    fun addNote(title: String?, content: String, images: List<Pair<Uri, Int>>) {
        val finalTitle = if (title.isNullOrBlank()) {
            "Untitled ${untitledCounter++}"
        } else {
            title
        }
        val embeddedImages = mutableListOf<String>()
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

        val note = Note(
            title = finalTitle,
            content = finalContent.trim(),
            date = System.currentTimeMillis(),
            images = embeddedImages,
            summary = summarizer?.let { it.fallbackSummary(finalContent) } ?: finalContent.take(200)
        )
        _notes.add(0, note) // newest first
        pin?.let { store?.saveNotes(_notes, it) }
        summarizer?.let { sum ->
            viewModelScope.launch {
                val summary = sum.summarize(finalContent)
                _notes[0] = _notes[0].copy(summary = summary)
                pin?.let { store?.saveNotes(_notes, it) }
            }
        }
    }

    fun deleteNote(index: Int) {
        if (index in _notes.indices) {
            _notes.removeAt(index)
            pin?.let { store?.saveNotes(_notes, it) }
        }
    }

    fun updateNote(index: Int, title: String?, content: String, images: List<String>) {
        if (index in _notes.indices) {
            val note = _notes[index]
            val finalTitle = if (title.isNullOrBlank()) note.title else title
            val updated = note.copy(
                title = finalTitle,
                content = content.trim(),
                images = images,
                summary = summarizer?.let { it.fallbackSummary(content) } ?: content.take(200)
            )
            _notes[index] = updated
            pin?.let { store?.saveNotes(_notes, it) }
            summarizer?.let { sum ->
                viewModelScope.launch {
                    val summary = sum.summarize(updated.content)
                    _notes[index] = updated.copy(summary = summary)
                    pin?.let { store?.saveNotes(_notes, it) }
                }
            }
        }
    }

    fun exportNotes(context: Context) {
        val currentPin = pin ?: return
        store?.saveNotes(_notes, currentPin)
        val src = File(context.filesDir, "notes.enc")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        val dest = File(downloads, "notes.snarchive")
        try {
            src.copyTo(dest, overwrite = true)
        } catch (_: Exception) {}
    }

    fun importNotes(context: Context, uri: Uri, archivePin: String, overwrite: Boolean) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val imported = EncryptedNoteStore(context).loadNotesFromBytes(bytes, archivePin)
                if (overwrite) {
                    _notes.clear()
                }
                _notes.addAll(imported)
                pin?.let { store?.saveNotes(_notes, it) }
            }
        } catch (_: Exception) {}
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
}
