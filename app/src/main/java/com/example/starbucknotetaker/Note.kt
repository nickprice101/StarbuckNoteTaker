package com.example.starbucknotetaker

/**
 * Represents a note with title, textual content, creation date and optional attachments.
 */
data class Note(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val content: String,
    val date: Long = System.currentTimeMillis(),
    val images: List<String> = emptyList(),
    val files: List<NoteFile> = emptyList(),
    val summary: String = "",
)

/**
 * Represents an arbitrary file embedded within a note. The file is stored as
 * a base64 string along with its original name and MIME type so it can be
 * reconstructed and opened later.
 */
data class NoteFile(
    val name: String,
    val mime: String,
    val data: String,
)

