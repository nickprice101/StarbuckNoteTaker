package com.example.starbucknotetaker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.starbucknotetaker.ChecklistItem
import com.example.starbucknotetaker.asChecklistContent
import com.example.starbucknotetaker.richtext.RichTextDocument
import com.example.starbucknotetaker.ui.AddChecklistScreen
import com.example.starbucknotetaker.ui.AddNoteScreen
import com.example.starbucknotetaker.ui.EditChecklistScreen
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
    private var pendingBiometricNoteId: Long? = null
    private lateinit var navController: NavHostController

    @VisibleForTesting
    internal fun getNoteViewModelForTest(): NoteViewModel = noteViewModel

    private val biometricUnlockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(BIOMETRIC_LOG_TAG, "*** BIOMETRIC RESULT RECEIVED *** - resultCode=${result.resultCode}")
        
        when (result.resultCode) {
            RESULT_OK -> {
                Log.d(BIOMETRIC_LOG_TAG, "*** SUCCESS PATH ***")
                val data = result.data
                if (data == null) {
                    Log.e(BIOMETRIC_LOG_TAG, "*** SUCCESS PATH - NULL DATA ***")
                    return@registerForActivityResult
                }
                
                val success = data.getBooleanExtra("biometric_unlock_success", false)
                val usePinInstead = data.getBooleanExtra("use_pin_instead", false)
                val unlockedNoteId = data.getLongExtra("unlocked_note_id", -1L)
                val pinNoteId = data.getLongExtra("note_id_for_pin", -1L)
                
                Log.d(BIOMETRIC_LOG_TAG, "*** SUCCESS PATH - success=$success, usePinInstead=$usePinInstead, unlockedNoteId=$unlockedNoteId, pinNoteId=$pinNoteId ***")
                
                when {
                    success && unlockedNoteId != -1L -> {
                        Log.d(BIOMETRIC_LOG_TAG, "*** BIOMETRIC SUCCESS PATH - marking note unlocked and navigating to noteId=$unlockedNoteId ***")
                        
                        // Mark the note as unlocked in THIS ViewModel instance
                        noteViewModel.markNoteTemporarilyUnlocked(unlockedNoteId)
                        Log.d(BIOMETRIC_LOG_TAG, "*** Note $unlockedNoteId marked as temporarily unlocked ***")
                        
                        // Navigate to the note detail screen
                        try {
                            navController.navigate("detail/$unlockedNoteId") {
                                launchSingleTop = true
                            }
                            Log.d(BIOMETRIC_LOG_TAG, "*** Navigation to detail/$unlockedNoteId initiated ***")
                        } catch (e: Exception) {
                            Log.e(BIOMETRIC_LOG_TAG, "*** Navigation failed ***", e)
                            Toast.makeText(this, "Navigation failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    usePinInstead && pinNoteId != -1L -> {
                        Log.d(BIOMETRIC_LOG_TAG, "*** PIN FALLBACK PATH - setting pendingOpenNoteId=$pinNoteId ***")
                        noteViewModel.setPendingOpenNoteId(pinNoteId)
                    }
                    else -> {
                        Log.w(BIOMETRIC_LOG_TAG, "*** UNKNOWN SUCCESS PATH ***")
                    }
                }
            }
            RESULT_CANCELED -> {
                Log.d(BIOMETRIC_LOG_TAG, "*** CANCELED PATH ***")
            }
            else -> {
                Log.w(BIOMETRIC_LOG_TAG, "*** UNKNOWN RESULT CODE: ${result.resultCode} ***")
            }
        }
        pendingBiometricNoteId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        val pinManager = PinManager(applicationContext)
        setContent {
            StarbuckNoteTakerTheme {
                navController = rememberNavController()
                AppContent(navController, noteViewModel, pinManager) { noteId, noteTitle ->
                    // Callback for starting biometric unlock
                    Log.d(BIOMETRIC_LOG_TAG, "*** LAUNCHING BIOMETRIC UNLOCK for noteId=$noteId ***")
                    pendingBiometricNoteId = noteId
                    val biometricIntent = BiometricUnlockActivity.createIntent(this, noteId, noteTitle)
                    biometricUnlockLauncher.launch(biometricIntent)
                }
            }
        }

        handleReminderIntent(intent)
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
        handleShareIntent(intent)
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
fun AppContent(
    navController: NavHostController, 
    noteViewModel: NoteViewModel, 
    pinManager: PinManager,
    onStartBiometricUnlock: (Long, String) -> Unit
) {
    val context = LocalContext.current
    val summarizerState by noteViewModel.summarizerState.collectAsState()
    val summarizerEnabled by noteViewModel.summarizerEnabled.collectAsState()
    val pendingShare by noteViewModel.pendingShare.collectAsState()
    val pendingOpenNoteId by noteViewModel.pendingOpenNoteId.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val isPinSet = pinManager.isPinSet()
    var hasLoadedInitialPin by remember { mutableStateOf(false) }
    var pendingUnlockNoteId by remember { mutableStateOf<Long?>(null) }
    var biometricsEnabled by remember { mutableStateOf(pinManager.isBiometricEnabled()) }
    var showBiometricOptIn by remember { mutableStateOf(false) }

    val biometricManager = remember { BiometricManager.from(context) }
    val biometricStatus = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
    )
    val canUseBiometric = biometricsEnabled && biometricStatus == BiometricManager.BIOMETRIC_SUCCESS
    val startDestination = if (isPinSet) "list" else "pin_setup"

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
                onAddChecklist = { navController.navigate("add_checklist") },
                onAddEvent = { navController.navigate("add_event") },
                onOpenNote = { note ->
                    val isTemporarilyUnlocked = noteViewModel.isNoteTemporarilyUnlocked(note.id)
                    Log.d(BIOMETRIC_LOG_TAG, "*** NOTE_TAP: noteId=${note.id} locked=${note.isLocked} temporarilyUnlocked=$isTemporarilyUnlocked ***")

                    if (note.isLocked && !isTemporarilyUnlocked) {
                        if (canUseBiometric) {
                            Log.d(BIOMETRIC_LOG_TAG, "*** NOTE_TAP: Starting BiometricUnlockActivity for noteId=${note.id} ***")
                            onStartBiometricUnlock(note.id, note.title)
                        } else {
                            Log.d(BIOMETRIC_LOG_TAG, "*** NOTE_TAP: Starting PIN unlock for noteId=${note.id} ***")
                            noteViewModel.setPendingOpenNoteId(note.id)
                        }
                    } else {
                        Log.d(BIOMETRIC_LOG_TAG, "*** NOTE_TAP: Direct navigation to ${if (note.isLocked) "temporarily unlocked" else "unlocked"} note ***")
                        navController.navigate("detail/${note.id}") {
                            launchSingleTop = true
                        }
                    }
                },
                onDeleteNote = { noteId -> noteViewModel.deleteNote(noteId) },
                onRestoreNote = { note -> noteViewModel.restoreNote(note) },
                onSettings = { navController.navigate("settings") },
                summarizerState = summarizerState
            )
        }
        composable("add") {
            AddNoteScreen(
                onSave = { title, content, styledContent, images, files, linkPreviews, event ->
                    noteViewModel.addNote(title, content, styledContent, images, files, linkPreviews, event)
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
        composable("add_checklist") {
            AddChecklistScreen(
                onSave = { title, items ->
                    val content = items.asChecklistContent()
                    val styled = RichTextDocument.fromPlainText(content)
                    noteViewModel.addNote(
                        title = title,
                        content = content,
                        styledContent = styled,
                        images = emptyList(),
                        files = emptyList(),
                        linkPreviews = emptyList(),
                        event = null,
                        checklistItems = items,
                    )
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                summarizerState = summarizerState,
            )
        }
        composable("add_event") {
            AddNoteScreen(
                onSave = { title, content, styledContent, images, files, linkPreviews, event ->
                    if (event != null) {
                        noteViewModel.addNote(title, content, styledContent, images, files, linkPreviews, event)
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
            Log.d(BIOMETRIC_LOG_TAG, "*** DETAIL_SCREEN: Loaded for noteId=$noteId ***")
            
            if (noteId != null && note != null) {
                DisposableEffect(noteId) {
                    Log.d(BIOMETRIC_LOG_TAG, "*** DETAIL_SCREEN: Started for noteId=$noteId ***")
                    onDispose { 
                        Log.d(BIOMETRIC_LOG_TAG, "*** DETAIL_SCREEN: Disposed for noteId=$noteId ***")
                        noteViewModel.relockNote(noteId) 
                    }
                }
                NoteDetailScreen(
                    note = note,
                    onBack = { navController.popBackStack() },
                    onEdit = {
                        if (note.checklistItems != null) {
                            navController.navigate("edit_checklist/$noteId")
                        } else {
                            navController.navigate("edit/$noteId")
                        }
                    },
                    onLockRequest = {
                        noteViewModel.setNoteLock(note.id, true)
                        noteViewModel.markNoteTemporarilyUnlocked(note.id)
                    },
                    onUnlockRequest = { pendingUnlockNoteId = note.id },
                    openAttachment = { id -> noteViewModel.openAttachment(id) },
                    onChecklistChange = { items: List<ChecklistItem> ->
                        noteViewModel.updateChecklistItems(noteId, items)
                    }
                )
            } else {
                Log.w(BIOMETRIC_LOG_TAG, "*** DETAIL_SCREEN: Note not found, navigating back ***")
                navController.popBackStack()
            }
        }
        composable("edit/{noteId}") { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull()
            val note = noteId?.let { noteViewModel.getNoteById(it) }
            if (noteId != null && note != null) {
                EditNoteScreen(
                    note = note,
                    onSave = { title, content, styledContent, images, files, linkPreviews, event ->
                        noteViewModel.updateNote(noteId, title, content, styledContent, images, files, linkPreviews, event)
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
        composable("edit_checklist/{noteId}") { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull()
            val note = noteId?.let { noteViewModel.getNoteById(it) }
            if (noteId != null && note != null && note.checklistItems != null) {
                EditChecklistScreen(
                    note = note,
                    onSave = { title, items ->
                        val content = items.asChecklistContent()
                        val styled = RichTextDocument.fromPlainText(content)
                        noteViewModel.updateNote(
                            id = noteId,
                            title = title,
                            content = content,
                            styledContent = styled,
                            images = note.images,
                            files = note.files,
                            linkPreviews = note.linkPreviews,
                            event = note.event,
                            checklistItems = items,
                        )
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                    summarizerState = summarizerState,
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
                summarizerEnabled = summarizerEnabled,
                onSummarizerChanged = { enabled -> noteViewModel.setSummarizerEnabled(enabled) },
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
        Log.d(BIOMETRIC_LOG_TAG, "*** PIN_DIALOG: Showing for noteId=$noteId ***")
        if (note != null) {
            PinPromptDialog(
                title = "Unlock note",
                message = "Enter your PIN to open \"${note.title}\".",
                pinManager = pinManager,
                showBiometricOption = canUseBiometric,
                onBiometricRequested = {
                    Log.d(BIOMETRIC_LOG_TAG, "*** PIN_DIALOG: Biometric requested ***")
                    noteViewModel.clearPendingOpenNoteId()
                    if (canUseBiometric) {
                        onStartBiometricUnlock(noteId, note.title)
                    }
                },
                onDismiss = { 
                    Log.d(BIOMETRIC_LOG_TAG, "*** PIN_DIALOG: Dismissed ***")
                    noteViewModel.clearPendingOpenNoteId() 
                },
                onPinConfirmed = {
                    Log.d(BIOMETRIC_LOG_TAG, "*** PIN_DIALOG: Confirmed for noteId=$noteId ***")
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
