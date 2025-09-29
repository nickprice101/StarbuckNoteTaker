package com.example.starbucknotetaker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.starbucknotetaker.ui.AddNoteScreen
import com.example.starbucknotetaker.ui.EditNoteScreen
import com.example.starbucknotetaker.ui.NoteDetailScreen
import com.example.starbucknotetaker.ui.NoteEntryMode
import com.example.starbucknotetaker.ui.NoteListScreen
import com.example.starbucknotetaker.ui.PinEnterScreen
import com.example.starbucknotetaker.ui.PinSetupScreen
import com.example.starbucknotetaker.ui.SettingsScreen
import com.example.starbucknotetaker.ui.StarbuckNoteTakerTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity() {
    private val noteViewModel: NoteViewModel by viewModels()

    @VisibleForTesting
    internal fun getNoteViewModelForTest(): NoteViewModel = noteViewModel

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

        handleReminderIntent(intent)
        handleShareIntent(intent)
        handleBiometricNavigationIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
        handleShareIntent(intent)
        handleBiometricNavigationIntent(intent)
    }

    private fun handleBiometricNavigationIntent(intent: Intent?) {
        intent ?: return
        
        // Handle navigation from BiometricUnlockActivity
        val navigateToNote = intent.getLongExtra("navigate_to_note", -1L)
        if (navigateToNote != -1L) {
            Log.d(BIOMETRIC_LOG_TAG, "MainActivity: Received navigation intent for noteId=$navigateToNote")
            noteViewModel.setPendingUnlockNavigationNoteId(navigateToNote)
            intent.removeExtra("navigate_to_note")
            return
        }
        
        // Handle PIN fallback from BiometricUnlockActivity
        val showPinForNote = intent.getLongExtra("show_pin_for_note", -1L)
        if (showPinForNote != -1L) {
            Log.d(BIOMETRIC_LOG_TAG, "MainActivity: Received PIN request for noteId=$showPinForNote")
            noteViewModel.setPendingOpenNoteId(showPinForNote)
            intent.removeExtra("show_pin_for_note")
            return
        }
    }

    private fun handleReminderIntent(intent: Intent?) {
        val noteId = intent?.getLongExtra(EXTRA_NOTE_ID, -1L)?.takeIf { it > 0 } ?: return
        noteViewModel.handleReminderNavigation(noteId)
        intent.removeExtra(EXTRA_NOTE_ID)
    }

    private fun handleShareIntent(intent: Intent?) {
        val sourceIntent = intent ?: return
        val pending = when (sourceIntent.action) {
            Intent.ACTION_SEND -> parseShareSendIntent(sourceIntent)
            Intent.ACTION_SEND_MULTIPLE -> parseShareSendMultipleIntent(sourceIntent)
            else -> null
        } ?: return

        noteViewModel.setPendingShare(pending)
        persistSharedUris(pending)

        sourceIntent.action = null
        sourceIntent.type = null
        sourceIntent.removeExtra(Intent.EXTRA_TEXT)
        sourceIntent.removeExtra(Intent.EXTRA_SUBJECT)
        sourceIntent.removeExtra(Intent.EXTRA_TITLE)
        sourceIntent.removeExtra(Intent.EXTRA_STREAM)
        sourceIntent.clipData = null
    }

    private fun parseShareSendIntent(intent: Intent): PendingShare? {
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val stream = intent.getParcelableExtraUri(Intent.EXTRA_STREAM)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

        val images = mutableListOf<Uri>()
        val files = mutableListOf<Uri>()
        stream?.let { uri ->
            if (isImageType(resolveStreamType(intent, uri))) {
                images += uri
            } else {
                files += uri
            }
        }

        if (title.isNullOrBlank() && text.isNullOrBlank() && images.isEmpty() && files.isEmpty()) {
            return null
        }

        return PendingShare(
            title = title,
            text = text,
            images = images,
            files = files,
        )
    }

    private fun parseShareSendMultipleIntent(intent: Intent): PendingShare? {
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uris = intent.getParcelableArrayListExtraUris(Intent.EXTRA_STREAM)
            ?: extractClipDataUris(intent.clipData)

        if (title.isNullOrBlank() && text.isNullOrBlank() && (uris == null || uris.isEmpty())) {
            return null
        }

        val images = mutableListOf<Uri>()
        val files = mutableListOf<Uri>()
        uris?.forEach { uri ->
            if (isImageType(resolveStreamType(intent, uri))) {
                images += uri
            } else {
                files += uri
            }
        }

        return PendingShare(
            title = title,
            text = text,
            images = images,
            files = files,
        )
    }

    private fun resolveStreamType(intent: Intent, uri: Uri): String? {
        val explicitType = intent.type?.takeUnless { it == "*/*" }
        if (!explicitType.isNullOrBlank()) {
            return explicitType
        }
        return try {
            contentResolver.getType(uri) ?: explicitType
        } catch (_: SecurityException) {
            explicitType
        }
    }

    private fun isImageType(type: String?): Boolean {
        return type?.startsWith("image/") == true
    }

    private fun persistSharedUris(share: PendingShare) {
        val uris = share.images + share.files
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun extractClipDataUris(clipData: android.content.ClipData?): List<Uri>? {
        if (clipData == null || clipData.itemCount == 0) return null
        val uris = mutableListOf<Uri>()
        for (index in 0 until clipData.itemCount) {
            val uri = clipData.getItemAt(index)?.uri
            if (uri != null) {
                uris += uri
            }
        }
        return uris
    }

    private fun Intent.getParcelableExtraUri(name: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    private fun Intent.getParcelableArrayListExtraUris(name: String): List<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(name)
        }
    }

    companion object {
        const val ACTION_VIEW_NOTE_FROM_REMINDER = "com.example.starbucknotetaker.action.VIEW_NOTE"
        const val EXTRA_NOTE_ID = "extra_note_id"
    }
}

@Composable
fun AppContent(navController: NavHostController, noteViewModel: NoteViewModel, pinManager: PinManager) {
    val context = LocalContext.current
    val activity = remember(context) { context as AppCompatActivity }
    val executor = remember(activity) { ContextCompat.getMainExecutor(activity) }
    val biometricManager = remember(activity) { BiometricManager.from(activity) }
    val summarizerState by noteViewModel.summarizerState.collectAsState()
    val pendingShare by noteViewModel.pendingShare.collectAsState()
    val pendingOpenNoteId by noteViewModel.pendingOpenNoteId.collectAsState()
    val pendingUnlockNavigationNoteId by noteViewModel.pendingUnlockNavigationNoteId.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val isPinSet = pinManager.isPinSet()
    var hasLoadedInitialPin by remember { mutableStateOf(false) }
    var pendingUnlockNoteId by remember { mutableStateOf<Long?>(null) }
    var biometricsEnabled by remember { mutableStateOf(pinManager.isBiometricEnabled()) }
    var showBiometricOptIn by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val biometricStatus = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
    )
    val canUseBiometric = biometricsEnabled && biometricStatus == BiometricManager.BIOMETRIC_SUCCESS
    val startDestination = if (isPinSet) "list" else "pin_setup"

    // Handle direct navigation from BiometricUnlockActivity
    LaunchedEffect(pendingUnlockNavigationNoteId) {
        val noteId = pendingUnlockNavigationNoteId ?: return@LaunchedEffect
        Log.d(BIOMETRIC_LOG_TAG, "AppContent: Direct navigation to noteId=$noteId")
        
        val note = noteViewModel.getNoteById(noteId)
        if (note != null) {
            navController.navigate("detail/$noteId") {
                launchSingleTop = true
                popUpTo("list") { inclusive = false }
            }
            noteViewModel.clearPendingUnlockNavigationNoteId()
        }
    }

    LaunchedEffect(isPinSet) {
        if (!isPinSet) {
            hasLoadedInitialPin = false
            biometricsEnabled = false
        } else if (!hasLoadedInitialPin) {
            val storedPin = pinManager.getStoredPin()
            if (storedPin != null) {
                noteViewModel.loadNotes(context, storedPin)
                hasLoadedInitialPin = true
                biometricsEnabled = pinManager.isBiometricEnabled()
            }
        }
    }

    LaunchedEffect(noteViewModel) {
        noteViewModel.reminderNavigation.collectLatest { noteId ->
            val note = noteViewModel.getNoteById(noteId)
            if (note == null) {
                Toast.makeText(context, "Note is no longer available", Toast.LENGTH_SHORT).show()
            } else if (note.isLocked && !noteViewModel.isNoteTemporarilyUnlocked(note.id)) {
                noteViewModel.setPendingOpenNoteId(note.id)
            } else {
                navController.navigate("detail/$noteId") {
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(pendingShare, navBackStackEntry?.destination?.route) {
        if (pendingShare == null) return@LaunchedEffect
        val currentRoute = navBackStackEntry?.destination?.route
        if (currentRoute != "pin_enter" && currentRoute != "pin_setup") {
            navController.navigate("add") {
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("pin_setup") {
            PinSetupScreen(pinManager = pinManager) { pin ->
                noteViewModel.loadNotes(context, pin)
                hasLoadedInitialPin = true
                biometricsEnabled = pinManager.isBiometricEnabled()
                showBiometricOptIn =
                    biometricStatus == BiometricManager.BIOMETRIC_SUCCESS && !biometricsEnabled
                navController.navigate("list") {
                    popUpTo("pin_setup") { inclusive = true }
                }
            }
        }
        composable("pin_enter") {
            PinEnterScreen(pinManager = pinManager) { pin ->
                noteViewModel.loadNotes(context, pin)
                hasLoadedInitialPin = true
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
                    Log.d(BIOMETRIC_LOG_TAG, "NOTE_TAP: noteId=${note.id} locked=${note.isLocked}")
                    
                    if (note.isLocked && !noteViewModel.isNoteTemporarilyUnlocked(note.id)) {
                        if (canUseBiometric) {
                            Log.d(BIOMETRIC_LOG_TAG, "NOTE_TAP: Starting BiometricUnlockActivity for noteId=${note.id}")
                            val biometricIntent = BiometricUnlockActivity.createIntent(context, note.id, note.title)
                            context.startActivity(biometricIntent)
                        } else {
                            Log.d(BIOMETRIC_LOG_TAG, "NOTE_TAP: Starting PIN unlock for noteId=${note.id}")
                            noteViewModel.setPendingOpenNoteId(note.id)
                        }
                    } else {
                        Log.d(BIOMETRIC_LOG_TAG, "NOTE_TAP: Direct navigation to unlocked note")
                        navController.navigate("detail/${note.id}") {
                            launchSingleTop = true
                        }
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
                    noteViewModel.clearPendingShare()
                    navController.popBackStack()
                },
                onBack = {
                    noteViewModel.clearPendingShare()
                    navController.popBackStack()
                },
                onDisablePinCheck = {},
                onEnablePinCheck = {},
                summarizerState = summarizerState,
                entryMode = NoteEntryMode.Note,
                prefill = pendingShare,
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
            Log.d(BIOMETRIC_LOG_TAG, "DETAIL_SCREEN: Loaded for noteId=$noteId")
            
            if (noteId != null && note != null) {
                DisposableEffect(noteId) {
                    Log.d(BIOMETRIC_LOG_TAG, "DETAIL_SCREEN: Started for noteId=$noteId")
                    onDispose { 
                        Log.d(BIOMETRIC_LOG_TAG, "DETAIL_SCREEN: Disposed for noteId=$noteId")
                        noteViewModel.relockNote(noteId) 
                    }
                }
                NoteDetailScreen(
                    note = note,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate("edit/$noteId") },
                    onLockRequest = {
                        noteViewModel.setNoteLock(note.id, true)
                        noteViewModel.markNoteTemporarilyUnlocked(note.id)
                    },
                    onUnlockRequest = { pendingUnlockNoteId = note.id },
                    openAttachment = { id -> noteViewModel.openAttachment(id) }
                )
            } else {
                Log.w(BIOMETRIC_LOG_TAG, "DETAIL_SCREEN: Note not found, navigating back")
                navController.popBackStack()
            }
        }
        composable("edit/{noteId}") { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull()
            val note = noteId?.let { noteViewModel.getNoteById(it) }
            if (noteId != null && note != null) {
                EditNoteScreen(
                    note = note,
                    onSave = { title, content, images, files, linkPreviews, event ->
                        noteViewModel.updateNote(noteId, title, content, images, files, linkPreviews, event)
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                    onDisablePinCheck = {},
                    onEnablePinCheck = {},
                    summarizerState = summarizerState,
                    openAttachment = { id -> noteViewModel.openAttachment(id) }
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

    if (showBiometricOptIn) {
        AlertDialog(
            onDismissRequest = {
                showBiometricOptIn = false
                pinManager.setBiometricEnabled(false)
                biometricsEnabled = false
            },
            title = { Text("Enable biometric unlock?") },
            text = {
                Text("Would you like to use your fingerprint or face to unlock your notes? You can change this later in Settings.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBiometricOptIn = false
                    if (biometricStatus == BiometricManager.BIOMETRIC_SUCCESS) {
                        pinManager.setBiometricEnabled(true)
                        biometricsEnabled = true
                        Toast.makeText(context, "Biometric unlock enabled", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Biometric unlock isn't available right now.",
                            Toast.LENGTH_SHORT
                        ).show()
                        pinManager.setBiometricEnabled(false)
                        biometricsEnabled = false
                    }
                }) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBiometricOptIn = false
                    pinManager.setBiometricEnabled(false)
                    biometricsEnabled = false
                }) {
                    Text("Not now")
                }
            }
        )
    }

    pendingOpenNoteId?.let { noteId ->
        val note = noteViewModel.getNoteById(noteId)
        Log.d(BIOMETRIC_LOG_TAG, "PIN_DIALOG: Showing for noteId=$noteId")
        if (note != null) {
            PinPromptDialog(
                title = "Unlock note",
                message = "Enter your PIN to open \"${note.title}\".",
                pinManager = pinManager,
                showBiometricOption = canUseBiometric,
                onBiometricRequested = {
                    Log.d(BIOMETRIC_LOG_TAG, "PIN_DIALOG: Biometric requested")
                    noteViewModel.clearPendingOpenNoteId()
                    if (canUseBiometric) {
                        val biometricIntent = BiometricUnlockActivity.createIntent(context, noteId, note.title)
                        context.startActivity(biometricIntent)
                    }
                },
                onDismiss = { 
                    Log.d(BIOMETRIC_LOG_TAG, "PIN_DIALOG: Dismissed")
                    noteViewModel.clearPendingOpenNoteId() 
                },
                onPinConfirmed = {
                    Log.d(BIOMETRIC_LOG_TAG, "PIN_DIALOG: Confirmed for noteId=$noteId")
                    noteViewModel.markNoteTemporarilyUnlocked(noteId)
                    noteViewModel.clearPendingOpenNoteId()
                    navController.navigate("detail/$noteId") {
                        launchSingleTop = true
                    }
                }
            )
        } else {
            noteViewModel.clearPendingOpenNoteId()
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
