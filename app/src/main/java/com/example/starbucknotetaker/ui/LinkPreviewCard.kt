package com.example.starbucknotetaker.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.starbucknotetaker.NoteLinkPreview
import java.io.File

@Composable
fun LinkPreviewCard(
    preview: NoteLinkPreview,
    awaitingCompletion: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onRemove: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val host = remember(preview.url) {
        runCatching { Uri.parse(preview.url).host ?: preview.url }
            .getOrDefault(preview.url)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .let { base -> if (onOpen != null) base.clickable(onClick = onOpen) else base },
        shape = RoundedCornerShape(12.dp),
        elevation = 4.dp,
    ) {
        Box(modifier = Modifier.background(MaterialTheme.colors.surface)) {
            Column(modifier = Modifier.padding(12.dp)) {
                when {
                    awaitingCompletion -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "URL detected. Waiting for link completion...")
                        }
                    }
                    isLoading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(text = "Loading preview…")
                        }
                    }
                    errorMessage != null -> {
                        Text(text = errorMessage, color = MaterialTheme.colors.error)
                    }
                    else -> {
                        val imageModel = preview.cachedImagePath?.let { File(it) }
                            ?: preview.imageUrl
                        imageModel?.let { model ->
                            Image(
                                painter = rememberAsyncImagePainter(model),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        preview.title?.takeIf { it.isNotBlank() }?.let { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.subtitle1,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.body2,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = host,
                            style = MaterialTheme.typography.caption,
                            color = Color.Gray,
                        )
                    }
                }
            }
            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Remove preview")
                }
            }
        }
    }
}
