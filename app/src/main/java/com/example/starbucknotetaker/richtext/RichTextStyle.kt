package com.example.starbucknotetaker.richtext

import androidx.compose.ui.graphics.Color

sealed class RichTextStyle {
    object Bold : RichTextStyle()
    object Italic : RichTextStyle()
    object Underline : RichTextStyle()
    data class Highlight(val color: Color) : RichTextStyle()
    data class TextColor(val color: Color) : RichTextStyle()
    /** Semantic Markdown link rendered as a compact citation pill. */
    data class Citation(val url: String) : RichTextStyle()
}

const val CITATION_URL_TAG = "citation_url"
