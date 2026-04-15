package com.example.starbucknotetaker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.LlamaEngine
import kotlinx.coroutines.delay

private val WITTY_PHRASES = listOf(
    "Brewing some AI magic ✨",
    "Connecting neurons… 🧠",
    "Assembling your thoughts 📝",
    "Channelling the oracle… 🔮",
    "Making it snappy 🚀",
    "Consulting the digital muse 🎨",
    "Polishing your prose ✍️",
    "Deep in thought… 💭",
    "Crunching the good stuff 🔬",
    "Almost there, bear with me 🐻",
)

/**
 * Semi-transparent overlay card displayed over the content area being updated
 * by an AI inference task.
 *
 * While [progress] is [LlamaEngine.InferenceProgress.Thinking] the overlay
 * shows a spinner and cycles through [WITTY_PHRASES] every ~2 seconds.  It
 * also streams the live partial token output from the model beneath the phrase.
 *
 * The overlay fades out automatically when [progress] transitions to
 * [LlamaEngine.InferenceProgress.Done], [LlamaEngine.InferenceProgress.Idle],
 * or [LlamaEngine.InferenceProgress.Error].
 *
 * @param progress   Current inference progress from [LlamaEngine.progress].
 * @param modifier   Optional layout modifier.
 */
@Composable
fun AiProgressOverlay(
    progress: LlamaEngine.InferenceProgress,
    modifier: Modifier = Modifier,
) {
    val isVisible = progress is LlamaEngine.InferenceProgress.Thinking ||
            progress is LlamaEngine.InferenceProgress.Throttled

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(durationMillis = 300)),
        exit  = fadeOut(animationSpec = tween(durationMillis = 500)),
        modifier = modifier,
    ) {
        AiProgressCard(progress = progress)
    }
}

@Composable
private fun AiProgressCard(progress: LlamaEngine.InferenceProgress) {
    var phraseIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(progress) {
        while (true) {
            delay(2_000L)
            phraseIndex = (phraseIndex + 1) % WITTY_PHRASES.size
        }
    }

    Card(
        backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.92f),
        elevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colors.primary,
                )
                Spacer(Modifier.width(10.dp))
                val phrase = when (progress) {
                    is LlamaEngine.InferenceProgress.Throttled ->
                        "Taking a breather 🌡️ — cooling down…"
                    else -> WITTY_PHRASES[phraseIndex]
                }
                Text(
                    text = phrase,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary,
                )
            }

            // Stream live token output beneath the phrase when available
            val partialText = (progress as? LlamaEngine.InferenceProgress.Thinking)?.partialText
            if (!partialText.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = partialText,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Compact single-line variant intended for use inside note list items.
 *
 * Only shows while [progress] references the given [taskId], so multiple
 * concurrent items don't all light up for the same task.
 */
@Composable
fun AiProgressInlineIndicator(
    progress: LlamaEngine.InferenceProgress,
    taskId: String,
    modifier: Modifier = Modifier,
) {
    val isActive = when (progress) {
        is LlamaEngine.InferenceProgress.Thinking -> progress.taskId == taskId
        is LlamaEngine.InferenceProgress.Throttled -> false
        else -> false
    }

    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(animationSpec = tween(200)),
        exit  = fadeOut(animationSpec = tween(400)),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colors.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "AI summarising…",
                style = MaterialTheme.typography.overline,
                color = MaterialTheme.colors.primary,
            )
        }
    }
}

/**
 * Helper to expose a stable display message for the current [progress].
 * Useful in snackbars or toast-style updates.
 */
fun inferenceStatusMessage(progress: LlamaEngine.InferenceProgress): String? = when (progress) {
    is LlamaEngine.InferenceProgress.Thinking  -> null // handled by overlay
    is LlamaEngine.InferenceProgress.Throttled -> "Device is warm — AI slowing down 🌡️"
    is LlamaEngine.InferenceProgress.Done      -> null // caller handles the result
    is LlamaEngine.InferenceProgress.Error     -> "AI error: ${progress.message}"
    LlamaEngine.InferenceProgress.Idle         -> null
}
