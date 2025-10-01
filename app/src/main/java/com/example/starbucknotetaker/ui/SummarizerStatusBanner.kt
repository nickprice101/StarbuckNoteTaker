package com.example.starbucknotetaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.Summarizer

@Composable
fun SummarizerStatusBanner(
    state: Summarizer.SummarizerState,
    modifier: Modifier = Modifier
) {
    when (state) {
        Summarizer.SummarizerState.Ready -> Unit
        Summarizer.SummarizerState.Loading -> {
            BannerContainer(
                background = MaterialTheme.colors.primary.copy(alpha = 0.08f),
                modifier = modifier
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colors.primary
                )
                Spacer(Modifier.size(12.dp))
                Text("Loading AI summarizer…")
            }
        }
        Summarizer.SummarizerState.Fallback -> Unit
        is Summarizer.SummarizerState.Error -> {
            BannerContainer(
                background = MaterialTheme.colors.error.copy(alpha = 0.1f),
                modifier = modifier
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colors.error
                )
                Spacer(Modifier.size(12.dp))
                Text("Summarizer unavailable: ${state.message}")
            }
        }
    }
}

@Composable
private fun BannerContainer(
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        color = background,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            content()
        }
    }
}
