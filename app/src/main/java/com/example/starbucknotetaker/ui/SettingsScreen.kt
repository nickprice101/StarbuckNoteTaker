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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onImport: (Uri, String, Boolean) -> Unit,
    onExport: (Uri) -> Unit,
    onDisablePinCheck: () -> Unit,
    onEnablePinCheck: () -> Unit
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

    DisposableEffect(Unit) {
        onDispose {
            onEnablePinCheck()
        }
    }

    if (showDialog && selectedUri != null) {
        var pin by remember { mutableStateOf("") }
        var overwrite by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {
                hideKeyboard()
                focusManager.clearFocus(force = true)
                showDialog = false
            },
            title = { Text("Import Archive") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("Archive PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
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
                    hideKeyboard()
                    focusManager.clearFocus(force = true)
                    selectedUri?.let { onImport(it, pin, overwrite) }
                    showDialog = false
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = {
                    hideKeyboard()
                    focusManager.clearFocus(force = true)
                    showDialog = false
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
        }
    }
}

