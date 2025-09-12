package com.example.starbucknotetaker

import android.net.Uri

/**
 * Represents a note with title, textual content, creation date and optional images.
 */
data class Note(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val content: String,
    val date: Long = System.currentTimeMillis(),
    val images: List<Uri> = emptyList()
)
