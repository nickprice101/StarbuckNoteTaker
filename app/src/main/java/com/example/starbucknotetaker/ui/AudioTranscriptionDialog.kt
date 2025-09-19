package com.example.starbucknotetaker.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AudioTranscriptionDialog(
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    val speechRecognizer = remember {
        SpeechRecognizer.createSpeechRecognizer(context)
    }

    var isRecording by remember { mutableStateOf(false) }
    var awaitingResult by remember { mutableStateOf(false) }
    var rms by remember { mutableStateOf(0f) }
    var partialText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                errorMessage = null
                partialText = ""
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
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) {
                    currentOnResult(text)
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

                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.body1,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.error
                    )
                }

                Spacer(modifier = Modifier.weight(1f, fill = true))

                val normalizedLevel = ((rms + 2f) / 10f).coerceIn(0f, 1f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    AudioLevelMeter(
                        level = if (isRecording) normalizedLevel else 0f,
                        modifier = Modifier.width(220.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    val buttonEnabled = isRecording || !awaitingResult
                    IconButton(
                        onClick = {
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
                                    speechRecognizer.startListening(intent)
                                }.onFailure { throwable ->
                                    awaitingResult = false
                                    errorMessage = throwable.message ?: "Unable to start recording"
                                }
                            }
                        },
                        enabled = buttonEnabled,
                        modifier = Modifier.size(56.dp)
                    ) {
                        if (isRecording) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop recording")
                        } else {
                            Icon(Icons.Default.Mic, contentDescription = "Start recording")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioLevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
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
                .height(48.dp),
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
