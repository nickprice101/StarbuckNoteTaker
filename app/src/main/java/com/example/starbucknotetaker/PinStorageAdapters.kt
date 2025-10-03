package com.example.starbucknotetaker

interface PinAttachmentStore {
    fun reencryptAttachment(oldPin: String, newPin: String, id: String): Boolean
}

interface PinNoteStore {
    fun loadNotes(pin: String): List<Note>
    fun saveNotes(notes: List<Note>, pin: String)
}
