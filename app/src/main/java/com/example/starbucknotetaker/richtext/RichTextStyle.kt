package com.example.starbucknotetaker.richtext

import androidx.compose.ui.graphics.Color

sealed class RichTextStyle {
    object Bold : RichTextStyle()
    object Italic : RichTextStyle()
    object Underline : RichTextStyle()
    data class Highlight(val color: Color) : RichTextStyle()
    data class TextColor(val color: Color) : RichTextStyle()
}
