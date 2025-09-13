package com.example.starbucknotetaker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import android.app.Activity

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
    val darkTheme = isSystemInDarkTheme()
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.primary.toArgb()
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colors = colors, content = content)
}

