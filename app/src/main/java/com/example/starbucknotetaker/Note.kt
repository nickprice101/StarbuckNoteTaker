package com.example.starbucknotetaker

/**
 * Represents a note with title, textual content, creation date and optional attachments.
 */
import com.example.starbucknotetaker.richtext.RichTextDocument

data class Note(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val content: String,
    val styledContent: RichTextDocument? = null,
    val date: Long = System.currentTimeMillis(),
    val images: List<NoteImage> = emptyList(),
    val files: List<NoteFile> = emptyList(),
    val linkPreviews: List<NoteLinkPreview> = emptyList(),
    val summary: String = "",
    val event: NoteEvent? = null,
    val isLocked: Boolean = false,
    val checklistItems: List<ChecklistItem>? = null,
)

val Note.isChecklist: Boolean
    get() = checklistItems != null

data class ChecklistItem(
    val text: String,
    val isChecked: Boolean = false,
)

fun List<ChecklistItem>.asChecklistContent(): String {
    if (isEmpty()) return ""
    val builder = StringBuilder()
    for (index in indices) {
        val item = this[index]
        if (index > 0) builder.append('\n')
        builder.append(if (item.isChecked) "[x]" else "[ ]")
        if (item.text.isNotBlank()) {
            builder.append(' ')
            builder.append(item.text)
        }
    }
    return builder.toString().trimEnd()
}

/**
 * Represents an arbitrary file embedded within a note. The file is stored as
 * a base64 string along with its original name and MIME type so it can be
 * reconstructed and opened later.
 */
data class NoteFile(
    val name: String,
    val mime: String,
    val attachmentId: String? = null,
    val data: String? = null,
)

/**
 * Represents an embedded image within a note. Images are stored externally via
 * the attachment store and referenced by a stable identifier. The deprecated
 * [data] property is temporarily retained so legacy notes encoded with base64
 * blobs can still be opened and migrated.
 */
data class NoteImage(
    val attachmentId: String? = null,
    val data: String? = null,
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
    val alarmMinutesBeforeStart: Int? = null,
    val notificationMinutesBeforeStart: Int? = null,
)

