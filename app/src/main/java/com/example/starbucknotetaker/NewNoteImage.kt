package com.example.starbucknotetaker

import android.net.Uri

/**
 * Represents an image selected while creating a note. Images can originate from
 * an external URI or be generated in-app (e.g. a sketch), which is delivered as
 * a base64 encoded PNG via [data]. Any rotation requested by the user is stored
 * in [rotation] so it can be applied when persisting the image.
 */
data class NewNoteImage(
    val uri: Uri? = null,
    val rotation: Int = 0,
    val data: String? = null,
)
