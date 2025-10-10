package com.example.starbucknotetaker.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.window.Dialog
import java.util.Locale
import kotlin.math.roundToInt

import com.example.starbucknotetaker.NotificationInterruptionManager

private data class RecognitionCandidate(
    val text: String,
    val confidence: Float?
)

@Composable
@OptIn(ExperimentalAnimationApi::class)
fun AudioTranscriptionDialog(
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
    onRequireAudioPermission: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnRequireAudioPermission by rememberUpdatedState(onRequireAudioPermission)

    val speechRecognizer = remember {
        SpeechRecognizer.createSpeechRecognizer(context)
    }

    var isRecording by remember { mutableStateOf(false) }
    var awaitingResult by remember { mutableStateOf(false) }
    var rms by remember { mutableStateOf(0f) }
    var partialText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showPermissionAction by remember { mutableStateOf(false) }

    val isTranscribing = isRecording || awaitingResult
    if (isTranscribing) {
        DisposableEffect(Unit) {
            NotificationInterruptionManager.blockNotifications()
            onDispose {
                NotificationInterruptionManager.releaseNotifications().forEach { it() }
            }
        }
    }

    val dialogView = LocalView.current
    val shouldKeepScreenOn = isRecording || awaitingResult
    DisposableEffect(dialogView, shouldKeepScreenOn) {
        if (shouldKeepScreenOn) {
            dialogView.keepScreenOn = true
        }
        onDispose {
            if (shouldKeepScreenOn) {
                dialogView.keepScreenOn = false
            }
        }
    }

    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                errorMessage = null
                partialText = ""
                showPermissionAction = false
            }

            override fun onBeginningOfSpeech() {
                isRecording = true
            }

            override fun onRmsChanged(rmsdB: Float) {
                rms = rmsdB
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                isRecording = false
            }

            override fun onError(error: Int) {
                isRecording = false
                awaitingResult = false
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    speechRecognizer.cancel()
                    showPermissionAction = true
                    currentOnRequireAudioPermission()
                } else {
                    showPermissionAction = false
                }
                errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out"
                    else -> "Unable to transcribe audio"
                }
            }

            override fun onResults(results: Bundle) {
                isRecording = false
                awaitingResult = false
                showPermissionAction = false
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val candidates = matches
                    ?.mapIndexedNotNull { index, text ->
                        val cleanedText = text?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                        val confidenceScore = scores?.getOrNull(index)?.takeIf { !it.isNaN() }
                        RecognitionCandidate(
                            text = cleanedText,
                            confidence = confidenceScore
                        )
                    }
                    .orEmpty()
                val bestCandidate = when {
                    candidates.isEmpty() -> null
                    candidates.all { it.confidence == null } -> candidates.first()
                    else -> candidates.maxByOrNull { it.confidence ?: Float.MIN_VALUE }
                }
                if (bestCandidate != null) {
                    partialText = ""
                    errorMessage = null
                    currentOnResult(bestCandidate.text)
                    currentOnDismiss()
                } else {
                    errorMessage = "No speech detected"
                }
            }

            override fun onPartialResults(partialResults: Bundle) {
                val matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                partialText = matches?.firstOrNull().orEmpty()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose {
            speechRecognizer.setRecognitionListener(null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.destroy()
        }
    }

    Dialog(
        onDismissRequest = {
            if (isRecording || awaitingResult) {
                speechRecognizer.cancel()
            }
            isRecording = false
            awaitingResult = false
            errorMessage = null
            showPermissionAction = false
            currentOnDismiss()
        }
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colors.surface,
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 320.dp)
                    .heightIn(min = 220.dp)
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Transcribe audio",
                        style = MaterialTheme.typography.h6
                    )
                    IconButton(
                        onClick = {
                            if (isRecording || awaitingResult) {
                                speechRecognizer.cancel()
                            }
                            isRecording = false
                            awaitingResult = false
                            errorMessage = null
                            showPermissionAction = false
                            currentOnDismiss()
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val statusMessage = when {
                    partialText.isNotBlank() -> partialText
                    awaitingResult && !isRecording -> "Processing transcription..."
                    isRecording -> "Listening..."
                    else -> "Tap the mic to begin recording."
                }

                AnimatedContent(
                    targetState = statusMessage,
                    modifier = Modifier.fillMaxWidth(),
                    label = "statusMessage"
                ) { message ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colors.primary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colors.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null
                            )
                            Text(
                                text = message,
                                style = MaterialTheme.typography.body1,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                errorMessage?.let { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.error,
                            modifier = Modifier.weight(1f)
                        )
                        if (showPermissionAction) {
                            TextButton(onClick = { currentOnRequireAudioPermission() }) {
                                Text("Grant access")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f, fill = true))

                val normalizedLevel = ((rms + 2f) / 10f).coerceIn(0f, 1f)
                val recordButtonSize = 72.dp
                val buttonEnabled = isRecording || !awaitingResult
                val buttonColor by animateColorAsState(
                    targetValue = if (isRecording) {
                        MaterialTheme.colors.primary
                    } else {
                        MaterialTheme.colors.surface
                    }
                )
                val buttonElevation by animateDpAsState(
                    targetValue = if (isRecording) 12.dp else 6.dp
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = buttonColor,
                        elevation = buttonElevation,
                        modifier = Modifier
                            .size(recordButtonSize)
                            .clip(CircleShape)
                            .clickable(enabled = buttonEnabled) {
                                if (isRecording) {
                                    speechRecognizer.stopListening()
                                } else if (!awaitingResult) {
                                    errorMessage = null
                                    partialText = ""
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                        )
                                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                    }
                                    runCatching {
                                        awaitingResult = true
                                        showPermissionAction = false
                                        speechRecognizer.startListening(intent)
                                    }.onFailure { throwable ->
                                        awaitingResult = false
                                        errorMessage = throwable.message ?: "Unable to start recording"
                                        showPermissionAction = false
                                    }
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val iconModifier = Modifier.size(recordButtonSize * 0.6f)
                            if (isRecording) {
                                StopRecordingIcon(modifier = iconModifier)
                            } else {
                                StartRecordingIcon(modifier = iconModifier)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    AudioLevelMeter(
                        level = if (isRecording) normalizedLevel else 0f,
                        meterHeight = recordButtonSize * 0.75f,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioLevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
    meterHeight: Dp = 48.dp,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = "Input level",
            style = MaterialTheme.typography.caption
        )
        Spacer(modifier = Modifier.height(8.dp))
        val blockCount = 8
        val activeBlocks = (level.coerceIn(0f, 1f) * blockCount).roundToInt()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(meterHeight),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(blockCount) { index ->
                val isActive = index < activeBlocks
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isActive) MaterialTheme.colors.primary
                            else MaterialTheme.colors.onSurface.copy(alpha = 0.15f)
                        )
                )
            }
        }
    }
}

@Composable
private fun StartRecordingIcon(modifier: Modifier = Modifier) {
    val outlineColor = Color(0xFFFF69B4)
    val fillColor = Color(0xFFE53935)
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val strokeWidth = radius * 0.18f
        drawCircle(
            color = outlineColor,
            radius = radius - strokeWidth / 2f,
            style = Stroke(width = strokeWidth)
        )
        drawCircle(
            color = fillColor,
            radius = radius * 0.55f
        )
    }
}

@Composable
private fun StopRecordingIcon(modifier: Modifier = Modifier) {
    val outlineColor = Color(0xFFFF69B4)
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val strokeWidth = radius * 0.18f
        drawCircle(
            color = outlineColor,
            radius = radius - strokeWidth / 2f,
            style = Stroke(width = strokeWidth)
        )
        val squareSide = radius * 1.1f
        val topLeft = Offset(
            x = center.x - squareSide / 2f,
            y = center.y - squareSide / 2f
        )
        drawRect(
            color = Color.Black,
            topLeft = topLeft,
            size = Size(squareSide, squareSide)
        )
    }
}

private fun FloatArray.getOrNull(index: Int): Float? = if (index in indices) this[index] else null
