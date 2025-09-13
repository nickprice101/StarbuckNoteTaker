package com.example.starbucknotetaker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Pink = Color(0xFFEC1A55)

private val LightColors = lightColors(
    primary = Pink,
    primaryVariant = Pink,
    secondary = Pink
)

private val DarkColors = darkColors(
    primary = Pink,
    primaryVariant = Pink,
    secondary = Pink
)

@Composable
fun StarbuckNoteTakerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colors = colors, content = content)
}

