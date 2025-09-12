package com.example.starbucknotetaker

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

/**
 * ViewModel storing notes in memory.
 */
class NoteViewModel : ViewModel() {
    private val _notes = mutableStateListOf<Note>()
    val notes: List<Note> = _notes

    private var untitledCounter = 1

    fun addNote(title: String?, content: String, images: List<Uri>) {
        val finalTitle = if (title.isNullOrBlank()) {
            "Untitled ${untitledCounter++}"
        } else {
            title
        }
        val note = Note(
            title = finalTitle,
            content = content,
            date = System.currentTimeMillis(),
            images = images
        )
        _notes.add(0, note) // newest first
    }
}
