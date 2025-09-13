package com.example.starbucknotetaker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.example.starbucknotetaker.PinManager

private class PinVisualTransformation(private val revealLast: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val out = buildString {
            text.text.forEachIndexed { index, c ->
                append(if (revealLast && index == text.text.lastIndex) c else '*')
            }
        }
        return TransformedText(AnnotatedString(out), OffsetMapping.Identity)
    }
}

@Composable
fun PinSetupScreen(pinManager: PinManager, onDone: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(pin) {
        if (reveal) {
            delay(1000)
            reveal = false
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val message = if (firstPin == null) {
        "Create a custom PIN"
    } else {
        "Please confirm your PIN."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, modifier = Modifier.padding(bottom = 16.dp))
        TextField(
            value = pin,
            onValueChange = { input ->
                if (input.length <= 6 && input.all { it.isDigit() }) {
                    pin = input
                    reveal = true
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PinVisualTransformation(reveal),
            singleLine = true,
            textStyle = androidx.compose.material.LocalTextStyle.current.copy(
                textAlign = TextAlign.Center,
                fontSize = 64.sp
            ),
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                textColor = Color.Black
            ),
            modifier = Modifier
                .width(200.dp)
                .focusRequester(focusRequester)
        )
        if (error != null) {
            Text(error!!, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
        }
        Button(
            onClick = {
                if (pin.length < 4) {
                    error = "PIN must be 4-6 digits"
                } else if (firstPin == null) {
                    firstPin = pin
                    pin = ""
                    error = null
                } else {
                    if (pin == firstPin) {
                        pinManager.setPin(pin)
                        onDone(pin)
                    } else {
                        error = "PINs do not match"
                        pin = ""
                    }
                }
            },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(if (firstPin == null) "Next" else "Save")
        }
    }
}

@Composable
fun PinEnterScreen(pinManager: PinManager, onSuccess: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val storedPinLength = remember { pinManager.getPinLength() }

    LaunchedEffect(pin) {
        if (reveal) {
            delay(1000)
            reveal = false
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .align(Alignment.TopCenter)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Please enter your PIN.", modifier = Modifier.padding(bottom = 16.dp))
            TextField(
                value = pin,
                onValueChange = { input ->
                    if (input.length <= 6 && input.all { it.isDigit() }) {
                        pin = input
                        reveal = true
                        error = false
                        if (input.length == storedPinLength) {
                            if (pinManager.checkPin(input)) {
                                onSuccess(input)
                            } else {
                                error = true
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PinVisualTransformation(reveal),
                singleLine = true,
                textStyle = androidx.compose.material.LocalTextStyle.current.copy(
                    textAlign = TextAlign.Center,
                    fontSize = 64.sp
                ),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    textColor = Color.Black
                ),
                modifier = Modifier
                    .width(200.dp)
                    .focusRequester(focusRequester)
            )
            if (error) {
                Text("Incorrect PIN", color = Color.Red, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
