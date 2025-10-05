package com.example.starbucknotetaker.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.PinManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    pinManager: PinManager,
    biometricEnabled: Boolean,
    onBiometricChanged: (Boolean) -> Unit,
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
    val context = LocalContext.current
    val activity = remember(context) { context as AppCompatActivity }
    val executor = remember(activity) { ContextCompat.getMainExecutor(activity) }
    val biometricManager = remember(activity) { BiometricManager.from(activity) }
    val biometricStatus = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
    )
    val biometricAvailable = biometricStatus == BiometricManager.BIOMETRIC_SUCCESS
    val biometricNeedsEnrollment = biometricStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
    val biometricStatusMessage = when (biometricStatus) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
            "No biometrics enrolled. Add a fingerprint or face in system settings."
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            "Biometric hardware is currently unavailable."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
            "Biometric hardware is not available on this device."
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
            "A security update is required before biometrics can be used."
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
            "Biometric authentication is not supported on this device."
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN ->
            "Biometric availability is currently unknown."
        else -> null
    }
    val isPinCurrentlySet = pinManager.isPinSet()
    var biometricChecked by remember { mutableStateOf(biometricEnabled) }
    var biometricTarget by remember { mutableStateOf<Boolean?>(null) }
    var biometricInProgress by remember { mutableStateOf(false) }
    var biometricToggleError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(biometricEnabled) {
        biometricChecked = biometricEnabled
    }

    LaunchedEffect(isPinCurrentlySet) {
        if (!isPinCurrentlySet && biometricEnabled) {
            pinManager.setBiometricEnabled(false)
            biometricChecked = false
            onBiometricChanged(false)
        }
    }

    val biometricPrompt = remember(activity, executor) {
        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val target = biometricTarget
                if (target != null) {
                    pinManager.setBiometricEnabled(target)
                    onBiometricChanged(target)
                    biometricChecked = target
                    biometricToggleError = null
                }
                biometricTarget = null
                biometricInProgress = false
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                biometricToggleError = when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> null
                    else -> errString.toString()
                }
                biometricChecked = biometricEnabled
                biometricTarget = null
                biometricInProgress = false
            }
        })
    }

    LaunchedEffect(biometricTarget) {
        val target = biometricTarget ?: return@LaunchedEffect
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (target) "Enable biometric unlock" else "Disable biometric unlock")
            .setSubtitle(
                if (target) {
                    "Authenticate to enable unlocking notes with biometrics."
                } else {
                    "Authenticate to disable biometric unlock."
                }
            )
            .setNegativeButtonText("Cancel")
            .build()
        biometricPrompt.authenticate(promptInfo)
    }
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

    val scrollState = rememberScrollState()

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
                .verticalScroll(scrollState)
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Biometric unlock", style = MaterialTheme.typography.h6)
                        Text(
                            "Use fingerprint or face authentication to unlock notes.",
                            style = MaterialTheme.typography.body2
                        )
                    }
                    Switch(
                        modifier = Modifier.testTag("biometric_toggle"),
                        checked = biometricChecked,
                        onCheckedChange = { checked ->
                            if (biometricInProgress) return@Switch
                            biometricToggleError = null
                            if (checked == biometricEnabled) {
                                biometricChecked = biometricEnabled
                                return@Switch
                            }
                            if (!isPinCurrentlySet && checked) {
                                biometricChecked = false
                                biometricToggleError = "Set a PIN before enabling biometrics."
                                pinManager.setBiometricEnabled(false)
                                onBiometricChanged(false)
                                return@Switch
                            }
                            if (checked && !biometricAvailable) {
                                biometricChecked = biometricEnabled
                                biometricToggleError = biometricStatusMessage
                                    ?: "Biometrics are unavailable."
                                return@Switch
                            }
                            if (!checked && !biometricAvailable) {
                                biometricChecked = false
                                pinManager.setBiometricEnabled(false)
                                onBiometricChanged(false)
                                biometricToggleError = null
                                return@Switch
                            }
                            biometricChecked = checked
                            biometricTarget = checked
                            biometricInProgress = true
                        },
                        enabled = !biometricInProgress && (isPinCurrentlySet || biometricChecked)
                    )
                }
                if (!isPinCurrentlySet) {
                    Text(
                        "Set a PIN before enabling biometric unlock.",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.error
                    )
                }
                if (!biometricAvailable && biometricStatusMessage != null) {
                    Text(
                        biometricStatusMessage,
                        style = MaterialTheme.typography.caption,
                        color = if (biometricNeedsEnrollment) {
                            MaterialTheme.colors.primary
                        } else {
                            MaterialTheme.colors.error
                        }
                    )
                }
                if (biometricInProgress) {
                    Text(
                        "Awaiting biometric confirmation...",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary
                    )
                }
                biometricToggleError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption
                    )
                }
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
                                    val stored = pinManager.getStoredPin()
                                    if (stored != null) {
                                        onPinChanged(stored)
                                        pinChangeError = null
                                        pinChangeMessage = "PIN updated successfully."
                                        currentPin = ""
                                        newPin = ""
                                        confirmPin = ""
                                        currentPinVerified = false
                                    } else {
                                        pinChangeError = "Unable to read updated PIN."
                                        pinChangeMessage = null
                                    }
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

