package com.example.starbucknotetaker.ui

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.starbucknotetaker.Note

@Composable
fun NoteDetailScreen(note: Note, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(note.title) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            }
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            val lines = remember(note.content) { note.content.lines() }
            lines.forEach { line ->
                val trimmed = line.trim()
                if (isImageUrl(trimmed)) {
                    Image(
                        painter = rememberAsyncImagePainter(trimmed),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                } else {
                    val annotated = buildAnnotatedString {
                        var lastIndex = 0
                        val matcher = Patterns.WEB_URL.matcher(trimmed)
                        while (matcher.find()) {
                            val start = matcher.start()
                            val end = matcher.end()
                            append(trimmed.substring(lastIndex, start))
                            val url = trimmed.substring(start, end)
                            pushStringAnnotation(tag = "URL", annotation = url)
                            pushStyle(SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline))
                            append(url)
                            pop()
                            pop()
                            lastIndex = end
                        }
                        append(trimmed.substring(lastIndex))
                    }
                    ClickableText(text = annotated, onClick = { offset ->
                        annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { sa ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sa.item))
                            context.startActivity(intent)
                        }
                    }, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            note.images.forEach { uri ->
                Image(
                    painter = rememberAsyncImagePainter(uri),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }
        }
    }
}

private fun isImageUrl(url: String): Boolean {
    val lower = url.lowercase()
    return Patterns.WEB_URL.matcher(url).matches() &&
            (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp"))
}
