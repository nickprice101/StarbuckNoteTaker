package com.example.starbucknotetaker

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.starbucknotetaker.ui.AddNoteScreen
import com.example.starbucknotetaker.ui.NoteEntryMode
import com.example.starbucknotetaker.ui.NoteDetailScreen
import com.example.starbucknotetaker.ui.NoteListScreen
import com.example.starbucknotetaker.ui.PinEnterScreen
import com.example.starbucknotetaker.ui.PinSetupScreen
import com.example.starbucknotetaker.ui.EditNoteScreen
import com.example.starbucknotetaker.ui.StarbuckNoteTakerTheme
import com.example.starbucknotetaker.ui.SettingsScreen

class MainActivity : AppCompatActivity() {
    private val noteViewModel: NoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        val pinManager = PinManager(applicationContext)
        setContent {
            StarbuckNoteTakerTheme {
                val navController = rememberNavController()
                AppContent(navController, noteViewModel, pinManager)
            }
        }
    }
}

@Composable
fun AppContent(navController: NavHostController, noteViewModel: NoteViewModel, pinManager: PinManager) {
    val start = if (pinManager.isPinSet()) "pin_enter" else "pin_setup"
    val context = LocalContext.current
    val activity = remember(context) { context as AppCompatActivity }
    val executor = remember(activity) { ContextCompat.getMainExecutor(activity) }
    val biometricManager = remember(activity) { BiometricManager.from(activity) }
    val summarizerState by noteViewModel.summarizerState.collectAsState()
    var pendingOpenNoteId by remember { mutableStateOf<Long?>(null) }
    var pendingUnlockNoteId by remember { mutableStateOf<Long?>(null) }
    var biometricsEnabled by remember { mutableStateOf(pinManager.isBiometricEnabled()) }
    var biometricUnlockRequest by remember { mutableStateOf<BiometricUnlockRequest?>(null) }
    val biometricStatus = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
    )
    val canUseBiometric = biometricsEnabled && biometricStatus == BiometricManager.BIOMETRIC_SUCCESS
    val biometricPrompt = remember(activity, executor) {
        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val request = biometricUnlockRequest ?: return
                noteViewModel.markNoteTemporarilyUnlocked(request.noteId)
                biometricUnlockRequest = null
                pendingOpenNoteId = null
                navController.navigate("detail/${request.noteId}")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val request = biometricUnlockRequest
                biometricUnlockRequest = null
                if (request != null) {
                    pendingOpenNoteId = request.noteId
                }
            }
        })
    }

    LaunchedEffect(biometricUnlockRequest) {
        val request = biometricUnlockRequest ?: return@LaunchedEffect
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock note")
            .setSubtitle("Authenticate to open \"${request.title}\".")
            .setNegativeButtonText("Use PIN")
            .build()
        biometricPrompt.authenticate(promptInfo)
    }

    NavHost(navController = navController, startDestination = start) {
        composable("pin_setup") {
            PinSetupScreen(pinManager = pinManager) { pin ->
                noteViewModel.loadNotes(context, pin)
                navController.navigate("list") {
                    popUpTo("pin_setup") { inclusive = true }
                }
            }
        }
        composable("pin_enter") {
            PinEnterScreen(pinManager = pinManager) { pin ->
                noteViewModel.loadNotes(context, pin)
                navController.navigate("list") {
                    popUpTo("pin_enter") { inclusive = true }
                }
            }
        }
        composable("list") {
            NoteListScreen(
                notes = noteViewModel.notes,
                onAddNote = { navController.navigate("add") },
                onAddEvent = { navController.navigate("add_event") },
                onOpenNote = { note ->
                    if (note.isLocked && !noteViewModel.isNoteTemporarilyUnlocked(note.id)) {
                        if (canUseBiometric) {
                            pendingOpenNoteId = null
                            biometricUnlockRequest = BiometricUnlockRequest(note.id, note.title)
                        } else {
                            pendingOpenNoteId = note.id
                        }
                    } else {
                        navController.navigate("detail/${note.id}")
                    }
                },
                onDeleteNote = { noteId -> noteViewModel.deleteNote(noteId) },
                onSettings = { navController.navigate("settings") },
                summarizerState = summarizerState
            )
        }
        composable("add") {
            AddNoteScreen(
                onSave = { title, content, images, files, linkPreviews, event ->
                    noteViewModel.addNote(title, content, images, files, linkPreviews, event)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                onDisablePinCheck = {},
                onEnablePinCheck = {},
                summarizerState = summarizerState,
                entryMode = NoteEntryMode.Note,
            )
        }
        composable("add_event") {
            AddNoteScreen(
                onSave = { title, content, images, files, linkPreviews, event ->
                    if (event != null) {
                        noteViewModel.addNote(title, content, images, files, linkPreviews, event)
                    }
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                onDisablePinCheck = {},
                onEnablePinCheck = {},
                summarizerState = summarizerState,
                entryMode = NoteEntryMode.Event,
            )
        }
        composable("detail/{noteId}") { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull()
            val note = noteId?.let { noteViewModel.getNoteById(it) }
            if (note != null) {
                DisposableEffect(noteId) {
                    onDispose { noteId?.let { noteViewModel.relockNote(it) } }
                }
                NoteDetailScreen(
                    note = note,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate("edit/$noteId") },
                    onLockRequest = {
                        noteViewModel.setNoteLock(note.id, true)
                        noteViewModel.markNoteTemporarilyUnlocked(note.id)
                    },
                    onUnlockRequest = { pendingUnlockNoteId = note.id }
                )
            } else {
                navController.popBackStack()
            }
        }
        composable("edit/{noteId}") { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull()
            val note = noteId?.let { noteViewModel.getNoteById(it) }
            if (note != null && noteId != null) {
                EditNoteScreen(
                    note = note,
                    onSave = { title, content, images, files, linkPreviews, event ->
                        noteViewModel.updateNote(noteId, title, content, images, files, linkPreviews, event)
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                    onDisablePinCheck = {},
                    onEnablePinCheck = {},
                    summarizerState = summarizerState
                )
            } else {
                navController.popBackStack()
            }
        }
        composable("settings") {
            SettingsScreen(
                pinManager = pinManager,
                biometricEnabled = biometricsEnabled,
                onBiometricChanged = { enabled -> biometricsEnabled = enabled },
                onBack = { navController.popBackStack() },
                onImport = { uri, pin, overwrite -> noteViewModel.importNotes(context, uri, pin, overwrite) },
                onExport = { uri -> noteViewModel.exportNotes(context, uri) },
                onDisablePinCheck = {},
                onEnablePinCheck = {},
                onPinChanged = { newPin -> noteViewModel.updateStoredPin(newPin) }
            )
        }
    }

    pendingOpenNoteId?.let { noteId ->
        val note = noteViewModel.getNoteById(noteId)
        if (note != null) {
            PinPromptDialog(
                title = "Unlock note",
                message = "Enter your PIN to open \"${note.title}\".",
                pinManager = pinManager,
                showBiometricOption = canUseBiometric,
                onBiometricRequested = {
                    pendingOpenNoteId = null
                    if (canUseBiometric) {
                        biometricUnlockRequest = BiometricUnlockRequest(noteId, note.title)
                    }
                },
                onDismiss = { pendingOpenNoteId = null },
                onPinConfirmed = {
                    noteViewModel.markNoteTemporarilyUnlocked(noteId)
                    pendingOpenNoteId = null
                    navController.navigate("detail/$noteId")
                }
            )
        } else {
            pendingOpenNoteId = null
        }
    }

    pendingUnlockNoteId?.let { noteId ->
        val note = noteViewModel.getNoteById(noteId)
        if (note != null) {
            AlertDialog(
                onDismissRequest = { pendingUnlockNoteId = null },
                title = { Text("Remove PIN protection") },
                text = { Text("Remove the PIN lock from \"${note.title}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        noteViewModel.setNoteLock(noteId, false)
                        pendingUnlockNoteId = null
                    }) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingUnlockNoteId = null }) {
                        Text("Cancel")
                    }
                }
            )
        } else {
            pendingUnlockNoteId = null
        }
    }
}

@Composable
private fun PinPromptDialog(
    title: String,
    message: String,
    pinManager: PinManager,
    showBiometricOption: Boolean,
    onBiometricRequested: () -> Unit,
    onDismiss: () -> Unit,
    onPinConfirmed: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        if (input.length <= 6 && input.all { it.isDigit() }) {
                            pin = input
                            error = false
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (error) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Incorrect PIN", color = Color.Red)
                }
                if (showBiometricOption) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onBiometricRequested) {
                        Text("Use fingerprint/face")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (pinManager.checkPin(pin)) {
                        onPinConfirmed()
                    } else {
                        error = true
                    }
                },
                enabled = pin.length >= 4
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private data class BiometricUnlockRequest(val noteId: Long, val title: String)
