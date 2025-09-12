package com.example.starbucknotetaker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.example.starbucknotetaker.PinManager

@Composable
fun PinSetupScreen(pinManager: PinManager, onDone: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            label = { Text("Enter PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = confirmPin,
            onValueChange = { confirmPin = it },
            label = { Text("Confirm PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        if (error) {
            Text("PINs do not match", color = Color.Red, modifier = Modifier.padding(top = 8.dp))
        }
        Button(onClick = {
            if (pin.isNotEmpty() && pin == confirmPin) {
                pinManager.setPin(pin)
                onDone()
            } else {
                error = true
            }
        }, modifier = Modifier.padding(top = 16.dp)) {
            Text("Save")
        }
    }
}

@Composable
fun PinEnterScreen(pinManager: PinManager, onSuccess: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            label = { Text("Enter PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        if (error) {
            Text("Incorrect PIN", color = Color.Red, modifier = Modifier.padding(top = 8.dp))
        }
        Button(onClick = {
            if (pinManager.checkPin(pin)) {
                onSuccess()
            } else {
                error = true
            }
        }, modifier = Modifier.padding(top = 16.dp)) {
            Text("Unlock")
        }
    }
}
