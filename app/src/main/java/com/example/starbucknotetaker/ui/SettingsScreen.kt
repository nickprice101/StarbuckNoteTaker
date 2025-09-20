package com.example.starbucknotetaker.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.PinManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    pinManager: PinManager,
    onBack: () -> Unit,
    onImport: (Uri, String, Boolean) -> Boolean,
    onExport: (Uri) -> Unit,
    onDisablePinCheck: () -> Unit,
    onEnablePinCheck: () -> Unit,
    onPinChanged: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    val hideKeyboard = rememberKeyboardHider()
    val focusManager = LocalFocusManager.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            showDialog = true
        }
        onEnablePinCheck()
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            onExport(uri)
        }
        onEnablePinCheck()
    }

    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var currentPinVerified by remember { mutableStateOf(false) }
    var pinChangeError by remember { mutableStateOf<String?>(null) }
    var pinChangeMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            onEnablePinCheck()
        }
    }

    if (showDialog && selectedUri != null) {
        var pin by remember { mutableStateOf("") }
        var importError by remember { mutableStateOf<String?>(null) }
        var overwrite by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {
                hideKeyboard()
                focusManager.clearFocus(force = true)
                showDialog = false
                importError = null
            },
            title = { Text("Import Archive") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            pin = it
                            if (importError != null) {
                                importError = null
                            }
                        },
                        label = { Text("Archive PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        visualTransformation = PasswordVisualTransformation('*')
                    )
                    if (importError != null) {
                        Text(
                            importError!!,
                            color = MaterialTheme.colors.error,
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !overwrite, onClick = { overwrite = false })
                        Text("Append", Modifier.padding(start = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = overwrite, onClick = { overwrite = true })
                        Text("Overwrite", Modifier.padding(start = 8.dp))
                    }
                    if (overwrite) {
                        Text(
                            "This will delete existing notes.",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedUri?.let { uri ->
                        val success = onImport(uri, pin, overwrite)
                        if (success) {
                            hideKeyboard()
                            focusManager.clearFocus(force = true)
                            showDialog = false
                            importError = null
                        } else {
                            importError = "Incorrect PIN. Please try again."
                        }
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = {
                    hideKeyboard()
                    focusManager.clearFocus(force = true)
                    showDialog = false
                    importError = null
                }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        hideKeyboard()
                        focusManager.clearFocus(force = true)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("Import saved archive file (.snarchive), the original user PIN is required to import.")
            Button(onClick = {
                onDisablePinCheck()
                importLauncher.launch("*/*")
            }) {
                Text("Import archived notes file")
            }
            Text("Export content to an archive file (.snarchive), the original user PIN will be required to import later.")
            Button(onClick = {
                onDisablePinCheck()
                val formatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                val name = "notes-${formatter.format(Date())}.snarchive"
                exportLauncher.launch(name)
            }) {
                Text("Export archived notes file")
            }
            Divider()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Change PIN", style = MaterialTheme.typography.h6)
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { input ->
                        if (input.length <= 6 && input.all { it.isDigit() }) {
                            currentPin = input
                            pinChangeError = null
                            pinChangeMessage = null
                            if (currentPinVerified) {
                                currentPinVerified = false
                                newPin = ""
                                confirmPin = ""
                            }
                        }
                    },
                    label = { Text("Current PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation('*'),
                    enabled = !currentPinVerified
                )
                Button(
                    onClick = {
                        hideKeyboard()
                        focusManager.clearFocus(force = true)
                        if (pinManager.checkPin(currentPin)) {
                            currentPinVerified = true
                            pinChangeError = null
                            pinChangeMessage = "Current PIN confirmed."
                        } else {
                            pinChangeError = "Current PIN is incorrect."
                            pinChangeMessage = null
                        }
                    },
                    enabled = currentPin.length >= 4 && !currentPinVerified
                ) {
                    Text("Verify current PIN")
                }
                if (currentPinVerified) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { input ->
                            if (input.length <= 6 && input.all { it.isDigit() }) {
                                newPin = input
                                pinChangeError = null
                                pinChangeMessage = null
                            }
                        },
                        label = { Text("New PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation('*')
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { input ->
                            if (input.length <= 6 && input.all { it.isDigit() }) {
                                confirmPin = input
                                pinChangeError = null
                                pinChangeMessage = null
                            }
                        },
                        label = { Text("Confirm new PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation('*')
                    )
                    Button(
                        onClick = {
                            hideKeyboard()
                            focusManager.clearFocus(force = true)
                            when {
                                newPin.length !in 4..6 -> {
                                    pinChangeError = "PIN must be 4-6 digits."
                                    pinChangeMessage = null
                                }
                                newPin != confirmPin -> {
                                    pinChangeError = "PIN entries do not match."
                                    pinChangeMessage = null
                                }
                                pinManager.updatePin(currentPin, newPin) -> {
                                    onPinChanged(newPin)
                                    pinChangeError = null
                                    pinChangeMessage = "PIN updated successfully."
                                    currentPin = ""
                                    newPin = ""
                                    confirmPin = ""
                                    currentPinVerified = false
                                }
                                else -> {
                                    pinChangeError = "Unable to update PIN. Please verify your current PIN."
                                    pinChangeMessage = null
                                    currentPinVerified = false
                                }
                            }
                        },
                        enabled = newPin.isNotEmpty() && confirmPin.isNotEmpty()
                    ) {
                        Text("Update PIN")
                    }
                }
                pinChangeError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption
                    )
                }
                pinChangeMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colors.primary,
                        style = MaterialTheme.typography.caption
                    )
                }
                Text(
                    "Changing your PIN does not update the PIN for previously exported archives.",
                    style = MaterialTheme.typography.caption
                )
            }
        }
    }
}

