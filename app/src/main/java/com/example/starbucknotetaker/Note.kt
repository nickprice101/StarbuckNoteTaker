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
    val linkPreviews: List<NoteLinkPreview> = emptyList(),
    val summary: String = "",
    val event: NoteEvent? = null,
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

/**
 * Metadata describing a rich preview for a URL embedded in a note.
 */
data class NoteLinkPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val cachedImagePath: String? = null,
)

/**
 * Metadata describing an associated calendar event for a note entry.
 */
data class NoteEvent(
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val timeZone: String,
    val location: String? = null,
)

