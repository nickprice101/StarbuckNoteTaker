package com.example.starbucknotetaker

import android.content.Context
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
    private var pin: String? = null
    private var store: EncryptedNoteStore? = null

    fun loadNotes(context: Context, pin: String) {
        this.pin = pin
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
        val note = Note(
            title = finalTitle,
            content = content,
            date = System.currentTimeMillis(),
            images = images
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
}
