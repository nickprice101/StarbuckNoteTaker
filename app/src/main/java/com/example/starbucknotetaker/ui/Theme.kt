package com.example.starbucknotetaker.ui

import androidx.compose.material.MaterialTheme
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

@Composable
fun StarbuckNoteTakerTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = LightColors.primary.toArgb()
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = true
        }
    }
    MaterialTheme(colors = LightColors, content = content)
}

