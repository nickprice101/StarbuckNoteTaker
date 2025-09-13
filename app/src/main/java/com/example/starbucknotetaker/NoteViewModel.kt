package com.example.starbucknotetaker

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Patterns
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import java.net.URL

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

    fun loadNotes(context: Context, pin: String) {
        this.pin = pin
        this.context = context.applicationContext
        val s = EncryptedNoteStore(context)
        store = s
        _notes.clear()
        _notes.addAll(s.loadNotes(pin))
    }

    fun addNote(title: String?, content: String, images: List<Uri>) {
        val finalTitle = if (title.isNullOrBlank()) {
            "Untitled ${untitledCounter++}"
        } else {
            title
        }
        val embeddedImages = mutableListOf<String>()
        context?.let { ctx ->
            images.forEach { uri ->
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        embeddedImages.add(Base64.encodeToString(bytes, Base64.DEFAULT))
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
            images = embeddedImages
        )
        _notes.add(0, note) // newest first
        pin?.let { store?.saveNotes(_notes, it) }
    }

    fun deleteNote(index: Int) {
        if (index in _notes.indices) {
            _notes.removeAt(index)
            pin?.let { store?.saveNotes(_notes, it) }
        }
    }
    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return Patterns.WEB_URL.matcher(url).matches() &&
                (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                        lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp"))
    }
}
